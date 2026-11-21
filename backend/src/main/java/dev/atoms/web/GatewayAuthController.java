package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
final class GatewayAuthController {
    private final GatewayKeys keys;

    private final GatewaySessions sessions;

    GatewayAuthController(GatewayKeys keys, GatewaySessions sessions) {
        this.keys = keys;
        this.sessions = sessions;
    }

    record Registration(String provider, String token) {}
    record Refresh(@com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken) {}

    @GetMapping("/options")
    Object options() { return Map.of("google_client_id", sessions.googleClientId()); }

    @PostMapping("/register")
    Object register(@RequestBody Registration input) { return sessions.register(input.provider(), input.token()); }

    @PostMapping("/refresh")
    Object refresh(@RequestBody Refresh input) { return sessions.refresh(input.refreshToken()); }

    record CreateKey(String name, String description) {}

    @GetMapping("/me")
    Object me(HttpServletRequest request) {
        String tenant = tenant(request);
        if (request.getAttribute("gatewayJwt") instanceof GatewayJwt.Identity identity)
            return Map.of("user_id", identity.subject(), "tenant_id", tenant, "credential_type", "jwt");
        return Map.of("user_id", request.getAttribute("gatewayOwner"), "tenant_id", tenant,
                "credential_type", request.getAttribute("gatewayKey") == null ? "configured_token" : "api_key");
    }

    @GetMapping("/api-keys")
    Object list(HttpServletRequest request) { return keys.list(adminTenant(request)); }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    Object create(HttpServletRequest request, @RequestBody CreateKey input) {
        return keys.create(adminTenant(request), java.util.Objects.toString(input.name(), "").strip(),
                java.util.Objects.toString(input.description(), "").strip());
    }

    @DeleteMapping("/api-keys/{id}")
    Object revoke(HttpServletRequest request, @PathVariable String id) {
        keys.revoke(adminTenant(request), id);
        return Map.of("success", true);
    }

    @PostMapping("/refresh-key")
    Object rotate(HttpServletRequest request) {
        tenant(request);
        if (request.getAttribute("gatewayKey") instanceof GatewayKeys.Credential key) return keys.rotate(key);
        throw Store.error(403, "仅签发的 API Key 可以通过此接口轮换");
    }

    private static String tenant(HttpServletRequest request) {
        if (request.getAttribute("gatewayTenant") instanceof String tenant) return tenant;
        throw Store.error(401, "需要 Gateway 身份凭据");
    }

    private static String adminTenant(HttpServletRequest request) {
        String tenant = tenant(request);
        if (request.getAttribute("gatewayKey") != null || request.getAttribute("gatewayJwt") != null)
            throw Store.error(403, "需要配置令牌管理 API Keys");
        return tenant;
    }
}
