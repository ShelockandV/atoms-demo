package dev.atoms.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * 检查写请求的来源，并为匿名访客设置项目归属所需的 Cookie。
 *
 * <p>示例：同源 POST /api/projects 可通过来源检查，首次访问会收到 ATOMS_VISITOR Cookie。</p>
 */
@Component
public class RequestGuard extends OncePerRequestFilter {
    private final boolean secureCookie;
    private final java.util.Map<String, String> tenantTokens;

    public RequestGuard(@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                        org.springframework.core.env.Environment environment) {
        this.secureCookie = secureCookie;
        tenantTokens = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("atoms.gateway.tenant-tokens", org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class))
                .orElse(java.util.Map.of());
        if (tenantTokens.values().stream().anyMatch(String::isBlank)
                || new java.util.HashSet<>(tenantTokens.values()).size() != tenantTokens.size())
            throw new IllegalArgumentException("Gateway 租户令牌必须非空且互不相同");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Cache-Control", "no-store");
        if (req.getServletPath().startsWith("/api/v1/") && !tenantTokens.isEmpty()) {
            String authorization = java.util.Objects.toString(req.getHeader("Authorization"), "");
            var received = authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String tenant = tenantTokens.entrySet().stream().filter(entry -> java.security.MessageDigest.isEqual(
                    ("Bearer " + entry.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8), received))
                    .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
            if (tenant == null) {
                res.setHeader("WWW-Authenticate", "Bearer");
                res.sendError(401);
                return;
            }
            String key = UUID.nameUUIDFromBytes(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            req.setAttribute("gatewayOwner", "g-" + key.substring(2));
        }
        if (req.getRequestURI().startsWith("/api/") && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
            String origin = req.getHeader("Origin");
            String host = req.getHeader("Host");
            if (origin != null) {
                try {
                    var uri = URI.create(origin);
                    if (!uri.getRawAuthority().equalsIgnoreCase(host)) {
                        res.sendError(403, "Origin mismatch");
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    res.sendError(403);
                    return;
                }
            }
            if (req.getContentLengthLong() > 1_000_000) {
                res.sendError(413);
                return;
            }
        }
        if (req.getRequestURI().startsWith("/api/") && !req.getRequestURI().equals("/api/health")) {
            String visitor = null;
            if (req.getCookies() != null) for (var cookie : req.getCookies())
                if (cookie.getName().equals("ATOMS_VISITOR") && cookie.getValue().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                    visitor = cookie.getValue();
            if (visitor == null) visitor = UUID.randomUUID().toString();
            var session = req.getSession();
            synchronized (session) {
                session.setAttribute("owner", visitor);
            }
            var cookie = new Cookie("ATOMS_VISITOR", visitor);
            cookie.setHttpOnly(true);
            cookie.setSecure(req.isSecure() || secureCookie);
            cookie.setPath("/");
            cookie.setMaxAge(31536000);
            cookie.setAttribute("SameSite", "Strict");
            res.addCookie(cookie);
        }
        chain.doFilter(req, res);
    }
}
