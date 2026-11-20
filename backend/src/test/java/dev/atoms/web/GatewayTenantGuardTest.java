package dev.atoms.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 Gateway 租户令牌不会被访客 Cookie 替代。 */
class GatewayTenantGuardTest {
    @Test void requiresBearerAndSeparatesTenantOwners() throws Exception {
        var guard = new RequestGuard(false, new MockEnvironment()
                .withProperty("atoms.gateway.tenant-tokens.alpha", "token-alpha")
                .withProperty("atoms.gateway.tenant-tokens.beta", "token-beta"));
        var anonymous = request();
        var rejected = new MockHttpServletResponse();
        guard.doFilter(anonymous, rejected, (request, response) -> fail("没有凭据的请求进入了处理器"));
        assertEquals(401, rejected.getStatus());
        String alpha = authorized(guard, "token-alpha");
        String beta = authorized(guard, "token-beta");
        assertNotEquals(alpha, beta);
        assertEquals(alpha, authorized(guard, "token-alpha"));
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/sessions");
        request.setServletPath("/api/v1/sessions");
        request.setCookies(new jakarta.servlet.http.Cookie("ATOMS_VISITOR", "01234567-0000-4000-8000-000000000000"));
        return request;
    }

    private static String authorized(RequestGuard guard, String token) throws Exception {
        var request = request();
        request.addHeader("Authorization", "Bearer " + token);
        guard.doFilter(request, new MockHttpServletResponse(),
                (incoming, response) -> assertNotNull(incoming.getAttribute("gatewayOwner")));
        return (String) request.getAttribute("gatewayOwner");
    }
}
