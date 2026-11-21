package dev.atoms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@RestController
final class DaemonApprovalController {
    private static final String SOCKET = "/v1/ws/messages";
    private static final String API = "/api/v1/daemon/approvals";
    private final DaemonApprovals approvals;
    private final ObjectMapper json;
    private final Store store;

    private record DaemonIdentity(String tenant, String requester) {}

    DaemonApprovalController(DaemonApprovals approvals, ObjectMapper json, Store store) {
        this.approvals = approvals;
        this.json = json;
        this.store = store;
    }

    @GetMapping(SOCKET)
    void upgrade(HttpServletRequest request, HttpServletResponse response) throws Exception {
        GatewayWebSocketController.requireUpgrade(request);
        DaemonIdentity identity = daemonIdentity(request);
        GatewayWebSocketController.upgradeEndpoint(request, response, SOCKET, DaemonEndpoint.class,
                () -> new DaemonEndpoint(identity));
    }

    @GetMapping("/api/v1/daemon/status")
    Object status(HttpServletRequest request) {
        var identity = daemonIdentity(request);
        return Map.of("connected_daemons", store.connectedDaemons(identity.tenant(), identity.requester()));
    }

    private static DaemonIdentity daemonIdentity(HttpServletRequest request) {
        return new DaemonIdentity(tenant(request), requester(request));
    }

    static String requester(HttpServletRequest request) {
        if (!(request.getAttribute("gatewayOwner") instanceof String)) return null;
        String principal = switch (request.getAttribute("gatewayKey")) {
            case GatewayKeys.Credential key -> "key:" + key.id();
            case null, default -> request.getAttribute("gatewayJwt") instanceof GatewayJwt.Identity jwt
                    ? "jwt:" + jwt.subject() : "configured";
        };
        return java.util.UUID.nameUUIDFromBytes(principal.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @GetMapping(API)
    Object pending(HttpServletRequest request) { return Map.of("approvals", approvals.pending(approver(request))); }

    record Decision(String decision) {}

    @PostMapping(API + "/{id}/decision")
    Object decide(HttpServletRequest request, @PathVariable String id, @RequestBody Decision input) {
        approvals.decide(approver(request), id, java.util.Objects.toString(input.decision(), ""));
        return Map.of("success", true, "request_id", id);
    }

    private static String tenant(HttpServletRequest request) {
        if (request.getAttribute("gatewayOwner") instanceof String owner) return owner;
        throw Store.error(401, "需要 Gateway 身份凭据");
    }

    private static String approver(HttpServletRequest request) {
        String tenant = tenant(request);
        if (request.getAttribute("gatewayKey") != null || request.getAttribute("gatewayJwt") != null)
            throw Store.error(403, "daemon 审批需要租户配置令牌");
        return tenant;
    }

    private final class DaemonEndpoint extends Endpoint {
        private final DaemonIdentity identity;
        private final String connection = Store.id();
        private final Set<String> offered = new java.util.HashSet<>();
        private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        private final ArrayBlockingQueue<Map<String, Object>> outbound = new ArrayBlockingQueue<>(64);
        private volatile Thread worker;
        private boolean disconnected;
        private volatile long lastPong;

        private DaemonEndpoint(DaemonIdentity identity) {
            this.identity = identity;
        }

        @Override public void onOpen(Session session, EndpointConfig config) {
            session.setMaxTextMessageBufferSize(64 * 1024);
            session.setMaxIdleTimeout(Duration.ofMinutes(3).toMillis());
            session.getAsyncRemote().setSendTimeout(5000);
            session.addMessageHandler(String.class, (String text) -> receive(session, text));
            session.addMessageHandler(jakarta.websocket.PongMessage.class,
                    (jakarta.websocket.PongMessage pong) -> lastPong = System.nanoTime());
            worker = Thread.ofVirtual().unstarted(() -> pump(session));
            worker.start();
        }

        private synchronized void receive(Session session, String text) {
            if (disconnected) return;
            try {
                JsonNode event = json.readTree(text);
                if (event == null || !event.isObject()) throw Store.error(400, "无效消息");
                String type = event.path("type").asText();
                if ("disconnect".equals(type)) {
                    close(session, CloseReason.CloseCodes.NORMAL_CLOSURE);
                    return;
                }
                String messageId = event.path("message_id").asText();
                JsonNode payload = event.path("payload");
                if (Set.of("claim", "reply", "progress", "event").contains(type)) {
                    if (messageId.length() != 36) throw Store.error(400, "无效任务消息 ID");
                    handleTask(session, type, messageId, payload);
                    return;
                }
                if (!payload.isObject()) throw Store.error(400, "缺少消息 payload");
                String id = payload.path("request_id").asText();
                switch (type) {
                    case "approval_request" -> {
                        if (subscriptions.size() >= 128 && !subscriptions.contains(id))
                            throw Store.error(429, "当前连接的待处理审批过多");
                        approvals.submit(identity.tenant(), identity.requester(), new DaemonApprovals.Request(id, payload.path("agent").asText(),
                                payload.path("tool").asText(), payload.path("args").asText(), payload.path("thread_id").asText()));
                        subscriptions.add(id);
                    }
                    case "approval_resolved" -> {
                        subscriptions.remove(id);
                        approvals.closeLocally(identity.tenant(), identity.requester(), id);
                    }
                    default -> throw Store.error(400, "不支持该 daemon 消息类型");
                }
            } catch (ResponseStatusException invalid) {
                enqueueError(session, invalid.getReason());
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                enqueueError(session, "无效 JSON");
            } catch (RuntimeException failure) {
                enqueueError(session, "无法处理 daemon 消息");
            }
        }

        private void handleTask(Session session, String type, String id, JsonNode payload) {
            switch (type) {
                case "claim" -> {
                    boolean granted = store.claimDaemon(id, identity.tenant(), identity.requester(), connection);
                    offer(session, Map.of("type", "claim_ack", "message_id", id, "payload", Map.of("granted", granted)));
                }
                case "reply" -> {
                    String reply = payload.path("text").asText();
                    if (reply.isBlank() || reply.length() > 48000) throw Store.error(400, "回复须为 1 至 48000 字符");
                    store.replyDaemon(id, identity.tenant(), identity.requester(), connection, reply);
                }
                case "progress" -> store.recordDaemonEvent(id, identity.tenant(), identity.requester(), connection,
                        "DAEMON_PROGRESS", -1, payload.isObject() ? payload : json.createObjectNode());
                case "event" -> {
                    String eventType = payload.path("event_type").asText();
                    long sequence = payload.path("seq").asLong(-1);
                    if (!Set.of("TOOL_INVOKED", "TOOL_COMPLETED", "LLM_OUTPUT", "LLM_PARTIAL").contains(eventType)
                            || !payload.path("seq").isIntegralNumber() || !payload.path("seq").canConvertToLong() || sequence < 0)
                        throw Store.error(400, "无效 daemon 事件类型或序号");
                    store.recordDaemonEvent(id, identity.tenant(), identity.requester(), connection, eventType, sequence,
                            payload);
                }
                default -> throw new IllegalArgumentException(type);
            }
        }

        private void sendOffers(Session session) throws Exception {
            var offers = store.daemonOffers(identity.tenant(), identity.requester());
            var current = new java.util.HashSet<String>();
            for (var task : offers) {
                String id = (String) task.get("run_id");
                current.add(id);
                if (offered.contains(id)) continue;
                send(session, Map.of("type", "message", "message_id", id, "payload", Map.of(
                        "channel", "schedule", "thread_id", id, "sender", "scheduler",
                        "text", task.get("prompt"), "agent_name", task.get("agent_name"), "timestamp", task.get("created_at"))));
            }
            offered.retainAll(current);
            offered.addAll(current);
        }

        private void enqueueError(Session session, String message) {
            offer(session, Map.of("type", "error", "payload", Map.of("message", message)));
        }

        private void offer(Session session, Map<String, Object> message) {
            if (!outbound.offer(message)) close(session, CloseReason.CloseCodes.VIOLATED_POLICY);
        }

        private void pump(Session session) {
            try {
                store.connectDaemon(identity.tenant(), identity.requester(), connection);
                send(session, Map.of("type", "connected"));
                long pingInterval = Duration.ofSeconds(20).toNanos();
                long nextPing = System.nanoTime() + pingInterval;
                long observedPong = 0;
                while (session.isOpen() && !Thread.currentThread().isInterrupted()) {
                    var outboundMessage = outbound.poll(500, TimeUnit.MILLISECONDS);
                    if (outboundMessage != null) send(session, outboundMessage);
                    for (var decision : approvals.ready(identity.tenant(), identity.requester(), Set.copyOf(subscriptions))) {
                        if (!"closed".equals(decision.get("decision")))
                            send(session, Map.of("type", "approval_response", "payload", decision));
                        subscriptions.remove(decision.get("request_id"));
                    }
                    sendOffers(session);
                    if (System.nanoTime() >= nextPing) {
                        long pong = lastPong;
                        if (pong != observedPong) {
                            store.renewDaemonConnection(identity.tenant(), identity.requester(), connection);
                            observedPong = pong;
                        }
                        session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
                        nextPing = System.nanoTime() + pingInterval;
                    }
                }
            } catch (InterruptedException disconnected) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                close(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION);
            } finally {
                finishConnection();
            }
        }

        private void finishConnection() {
            synchronized (this) {
                disconnected = true;
            }
            boolean interrupted = Thread.interrupted();
            try {
                store.disconnectDaemon(identity.tenant(), identity.requester(), connection);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(DaemonApprovalController.class)
                        .warn("Could not release daemon claims after disconnect", failure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void send(Session session, Map<String, ?> message) throws Exception {
            session.getAsyncRemote().sendText(json.writeValueAsString(message)).get(5, TimeUnit.SECONDS);
        }

        @Override public void onClose(Session session, CloseReason reason) {
            var running = worker;
            if (running != null && running != Thread.currentThread()) running.interrupt();
        }

        @Override public void onError(Session session, Throwable error) {
            close(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION);
        }

        private void close(Session session, CloseReason.CloseCode code) {
            try {
                if (session.isOpen()) session.close(new CloseReason(code, ""));
            } catch (java.io.IOException ignored) {
                // The peer has already disconnected.
            }
        }
    }
}
