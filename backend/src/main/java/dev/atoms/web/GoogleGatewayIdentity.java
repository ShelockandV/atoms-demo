package dev.atoms.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.SignedJWT;
import dev.atoms.agent.persistence.Store;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.Set;

@Service
class GoogleGatewayIdentity {
    private final String audience;
    private final JWKSource<SecurityContext> keys;

    @org.springframework.beans.factory.annotation.Autowired
    GoogleGatewayIdentity(@Value("${atoms.gateway.google.client-id:}") String audience) throws Exception {
        this(audience, audience.isBlank() ? null : JWKSourceBuilder.<SecurityContext>create(
                URI.create("https://www.googleapis.com/oauth2/v3/certs").toURL(),
                new DefaultResourceRetriever(2000, 2000, 64000)).build());
    }

    GoogleGatewayIdentity(String audience, JWKSource<SecurityContext> keys) {
        this.audience = audience;
        this.keys = keys;
    }

    boolean enabled() { return keys != null; }

    String clientId() { return audience; }

    record Identity(String subject, String verifiedGmail) {}

    Identity verify(String raw) {
        if (keys == null || raw == null || raw.length() > 8192) return null;
        try {
            var token = SignedJWT.parse(raw);
            var claims = token.getJWTClaimsSet();
            var now = Instant.now();
            String kid = token.getHeader().getKeyID();
            String subject = claims.getSubject();
            if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm()) || kid == null
                    || claims.getIssuer() == null
                    || !Set.of("https://accounts.google.com", "accounts.google.com").contains(claims.getIssuer())
                    || !claims.getAudience().contains(audience)
                    || claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now))
                    || subject == null || !subject.matches("[A-Za-z0-9_-]{1,128}")) return null;
            var selector = new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).keyID(kid).build());
            for (var key : keys.get(selector, null)) {
                if (key instanceof RSAKey rsa && token.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) {
                    Object email = claims.getClaim("email");
                    String gmail = Boolean.TRUE.equals(claims.getClaim("email_verified")) && email instanceof String value
                            ? value.toLowerCase(java.util.Locale.ROOT) : null;
                    return new Identity(subject, gmail != null && gmail.matches("[a-z0-9.]+@gmail\\.com") ? gmail : null);
                }
            }
            return null;
        } catch (ParseException invalid) {
            return null;
        } catch (JOSEException unavailable) {
            throw Store.error(503, "Google 签名验证暂不可用");
        }
    }

    @PreDestroy
    void close() throws Exception {
        if (keys instanceof AutoCloseable closeable) closeable.close();
    }
}
