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
    @org.springframework.beans.factory.annotation.Autowired
    private GatewayKeys keys;
    @org.springframework.beans.factory.annotation.Autowired
    private GatewayRateLimits rateLimits;
    @org.springframework.beans.factory.annotation.Autowired
    private GatewayJwt jwt;
    @org.springframework.beans.factory.annotation.Autowired
    private GatewaySessions gatewaySessions;
    @org.springframework.beans.factory.annotation.Value("${atoms.memory.user-files.private-recall-enabled:false}")
    private boolean privateMemory;

    @jakarta.annotation.PostConstruct
    void validateJwtTenants() {
        if (jwt != null && jwt.enabled() && tenantTokens.isEmpty())
            throw new IllegalArgumentException("启用 Gateway JWT 时须配置至少一个已注册租户");
    }

    public RequestGuard(@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                        org.springframework.core.env.Environment environment) {
        this.secureCookie = secureCookie;
        tenantTokens = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("atoms.gateway.tenant-tokens", org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class))
                .orElse(java.util.Map.of());
        if (tenantTokens.keySet().stream().anyMatch(tenant -> tenant.isBlank() || tenant.length() > 128)
                || tenantTokens.values().stream().anyMatch(String::isBlank)
                || new java.util.HashSet<>(tenantTokens.values()).size() != tenantTokens.size())
            throw new IllegalArgumentException("Gateway 租户令牌必须非空且互不相同");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Cache-Control", "no-store");
        boolean compatibleApi = req.getServletPath().startsWith("/v1/");
        boolean signIn = "POST".equals(req.getMethod()) && Set.of("/api/v1/auth/register", "/api/v1/auth/refresh")
                .contains(req.getServletPath());
        boolean signInOptions = "GET".equals(req.getMethod()) && "/api/v1/auth/options".equals(req.getServletPath());
        if (!signIn && !signInOptions && (compatibleApi || req.getServletPath().startsWith("/api/v1/")) && !tenantTokens.isEmpty()) {
            String authorization = java.util.Objects.toString(req.getHeader("Authorization"), "");
            var received = authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String tenant = tenantTokens.entrySet().stream().filter(entry -> java.security.MessageDigest.isEqual(
                    ("Bearer " + entry.getValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8), received))
                    .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
            if (tenant == null && keys != null && authorization.startsWith("Bearer ")) {
                var managed = keys.authenticate(authorization.substring(7));
                if (managed != null && tenantTokens.containsKey(managed.tenant())) {
                    tenant = managed.tenant();
                    req.setAttribute("gatewayKey", managed);
                }
            }
            if (tenant == null && jwt != null && authorization.startsWith("Bearer ")) {
                var identity = jwt.authenticate(authorization.substring(7));
                if (identity != null && tenantTokens.containsKey(identity.tenant())
                        && (!identity.googleSession() || gatewaySessions.allows(identity.subject(), identity.tenant()))) {
                    tenant = identity.tenant();
                    req.setAttribute("gatewayJwt", identity);
                }
            }
            if (tenant == null) {
                res.setHeader("WWW-Authenticate", "Bearer");
                if (compatibleApi) {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":{\"message\":\"Invalid API key\",\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"}}");
                } else res.sendError(401);
                return;
            }
            String key = UUID.nameUUIDFromBytes(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            req.setAttribute("gatewayTenant", tenant);
            String tenantOwner = "g-" + key.substring(2);
            req.setAttribute("gatewayTenantOwner", tenantOwner);
            String owner = tenantOwner;
            if (privateMemory && req.getAttribute("gatewayJwt") instanceof GatewayJwt.Identity identity) {
                String userKey = UUID.nameUUIDFromBytes((tenantOwner + ":" + identity.subject())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                owner = "u-" + userKey.substring(2);
                req.setAttribute("gatewayPrivateOwner", owner);
            }
            req.setAttribute("gatewayOwner", owner);
        }
        if ((compatibleApi || req.getRequestURI().startsWith("/api/")) && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
            String origin = req.getHeader("Origin");
            String host = req.getHeader("Host");
            if (origin != null) {
                try {
                    var uri = URI.create(origin);
                    if (host == null || !host.equalsIgnoreCase(uri.getRawAuthority())) {
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
        if (signInOptions) {
            chain.doFilter(req, res);
            return;
        }
        if (signIn) {
            long retry = rateLimits.retryAfter("s-" + UUID.nameUUIDFromBytes(
                    req.getRemoteAddr().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().substring(2));
            if (retry > 0) {
                res.setStatus(429);
                res.setHeader("Retry-After", Long.toString(retry));
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (rateLimits != null && req.getAttribute("gatewayTenantOwner") instanceof String owner) {
            long retry = rateLimits.retryAfter(owner);
            if (retry > 0) {
                res.setStatus(429);
                res.setHeader("Retry-After", Long.toString(retry));
                res.setContentType("application/json");
                res.getWriter().write(compatibleApi
                        ? "{\"error\":{\"message\":\"Tenant request limit exceeded\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}"
                        : "{\"error\":\"rate_limit_exceeded\",\"message\":\"Tenant request limit exceeded\"}");
                return;
            }
        }
        if (compatibleApi || req.getRequestURI().startsWith("/api/") && !req.getRequestURI().equals("/api/health")) {
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
