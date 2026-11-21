package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
final class GatewayWebSocketController {
    private static final String PATH = "/api/v1/stream/ws";
    private final GatewayTaskEvents events;
    private final ObjectMapper json;

    GatewayWebSocketController(GatewayTaskEvents events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    @GetMapping(PATH)
    void upgrade(HttpServletRequest request, HttpServletResponse response) throws Exception {
        requireUpgrade(request);
        String owner = request.getAttribute("gatewayOwner") instanceof String tenant
                ? tenant : (String) request.getSession().getAttribute("owner");
        String cursor = request.getParameter("last_event_id");
        var subscription = events.subscribe(request.getParameter("workflow_id"),
                cursor == null ? request.getHeader("Last-Event-ID") : cursor, request.getParameter("types"), owner);
        upgradeEndpoint(request, response, PATH, StreamEndpoint.class, () -> new StreamEndpoint(subscription));
    }

    static void requireUpgrade(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !sameOrigin(origin, request.getHeader("Host")))
            throw Store.error(403, "WebSocket Origin mismatch");
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
            throw Store.error(400, "WebSocket upgrade is required");
    }

    static void upgradeEndpoint(HttpServletRequest request, HttpServletResponse response, String path,
                                Class<? extends Endpoint> endpoint, java.util.function.Supplier<Endpoint> instance)
            throws IOException, jakarta.websocket.DeploymentException {
        var container = (ServerContainer) request.getServletContext().getAttribute(ServerContainer.class.getName());
        if (container == null) throw Store.error(503, "WebSocket unavailable");
        var config = ServerEndpointConfig.Builder.create(endpoint, path)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override public <T> T getEndpointInstance(Class<T> type) {
                        return type.cast(instance.get());
                    }
                }).build();
        container.upgradeHttpToWebSocket(request, response, config, Map.of());
    }

    private static boolean sameOrigin(String origin, String host) {
        try {
            var uri = URI.create(origin);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getRawAuthority() != null
                    && uri.getRawAuthority().equalsIgnoreCase(host);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private final class StreamEndpoint extends Endpoint {
        private final GatewayTaskEvents.Subscription subscription;
        private volatile Thread worker;

        private StreamEndpoint(GatewayTaskEvents.Subscription subscription) { this.subscription = subscription; }

        @Override public void onOpen(Session session, EndpointConfig config) {
            session.setMaxIdleTimeout(Duration.ofMinutes(3).toMillis());
            session.getAsyncRemote().setSendTimeout(5000);
            worker = Thread.ofVirtual().unstarted(() -> pump(session));
            worker.start();
        }

        private void pump(Session session) {
            try {
                events.follow(subscription, event -> session.getAsyncRemote().sendText(json.writeValueAsString(event))
                                .get(5, TimeUnit.SECONDS),
                        () -> session.getBasicRemote().sendPing(ByteBuffer.allocate(0)));
                close(session, CloseReason.CloseCodes.NORMAL_CLOSURE);
            } catch (InterruptedException disconnected) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                close(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION);
            }
        }

        @Override public void onClose(Session session, CloseReason reason) { stop(); }

        @Override public void onError(Session session, Throwable error) {
            stop();
            close(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION);
        }

        private void stop() {
            var running = worker;
            if (running != null && running != Thread.currentThread()) running.interrupt();
        }

        private void close(Session session, CloseReason.CloseCode code) {
            try {
                if (session.isOpen()) session.close(new CloseReason(code, ""));
            } catch (IOException ignored) {
                // The peer has already disconnected.
            }
        }
    }
}
