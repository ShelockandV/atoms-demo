package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import dev.atoms.workspace.GatewayMemoryFiles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory/files")
final class GatewayMemoryController {
    private final GatewayMemoryFiles memory;

    GatewayMemoryController(GatewayMemoryFiles memory) {
        this.memory = memory;
    }

    @GetMapping
    Object list(HttpServletRequest request, @RequestParam(defaultValue = "") String path) {
        return Map.of("success", true, "files", memory.list(scope(request), path));
    }

    @GetMapping("/{*path}")
    Object read(HttpServletRequest request, @PathVariable String path) {
        return memory.read(scope(request), path.startsWith("/") ? path.substring(1) : path);
    }

    static String taskScope(HttpServletRequest request) {
        if (!(request.getAttribute("gatewayTenantOwner") instanceof String)) return null;
        if (request.getAttribute("gatewayJwt") != null && request.getAttribute("gatewayPrivateOwner") == null) return null;
        return scope(request);
    }

    static String scope(HttpServletRequest request) {
        if (!(request.getAttribute("gatewayTenantOwner") instanceof String tenant))
            throw Store.error(401, "记忆文件需要 Gateway 身份凭据");
        if (request.getAttribute("gatewayJwt") instanceof GatewayJwt.Identity identity)
            return "u-" + GatewayKeys.hash(tenant + ":" + identity.subject());
        return tenant;
    }
}
