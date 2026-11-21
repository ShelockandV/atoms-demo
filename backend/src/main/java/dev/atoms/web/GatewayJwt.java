package dev.atoms.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;

@Service
final class GatewayJwt {
    record Identity(String tenant, String subject, boolean googleSession) {}

    private final MACVerifier verifier;
    private final MACSigner signer;
    private final String issuer;
    private final String audience;

    GatewayJwt(@Value("${atoms.gateway.jwt.signing-key:}") String signingKey,
               @Value("${atoms.gateway.jwt.issuer:shannon-platform}") String issuer,
               @Value("${atoms.gateway.jwt.audience:}") String audience) throws JOSEException {
        this.issuer = issuer;
        this.audience = audience;
        if (signingKey.isBlank()) {
            verifier = null;
            signer = null;
            return;
        }
        byte[] secret = signingKey.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32 || issuer.isBlank())
            throw new IllegalArgumentException("Gateway JWT 需要至少 32 字节的签名密钥和非空签发者");
        verifier = new MACVerifier(secret);
        signer = new MACSigner(secret);
    }

    boolean enabled() { return verifier != null; }

    String issueGoogleSession(String tenant, String subject, Instant expires) {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .claim("tenant_id", tenant).claim("atoms_google_session", true)
                .jwtID(java.util.UUID.randomUUID().toString()).issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(expires));
        if (!audience.isBlank()) claims.audience(audience);
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        try {
            token.sign(signer);
            return token.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException("无法签发 Gateway JWT", failure);
        }
    }

    Identity authenticate(String bearer) {
        if (!enabled() || bearer.length() > 8192 || bearer.startsWith("atoms_")) return null;
        try {
            var token = SignedJWT.parse(bearer);
            if (!JWSAlgorithm.HS256.equals(token.getHeader().getAlgorithm()) || !token.verify(verifier)) return null;
            var claims = token.getJWTClaimsSet();
            var now = Instant.now();
            if (!issuer.equals(claims.getIssuer())
                    || (!audience.isBlank() && !claims.getAudience().contains(audience))
                    || claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now)))
                return null;
            String subject = claims.getSubject();
            String tenant = claims.getStringClaim("tenant_id");
            if (subject == null || subject.isBlank() || subject.length() > 128
                    || tenant == null || tenant.isBlank() || tenant.length() > 128) return null;
            return new Identity(tenant, subject, Boolean.TRUE.equals(claims.getClaim("atoms_google_session")));
        } catch (ParseException | JOSEException invalid) {
            return null;
        }
    }
}
