package dev.atoms.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoogleGatewayIdentityTest {
    @Test void verifiesSignatureAudienceIssuerAndExpiration() throws Exception {
        var trusted = new RSAKeyGenerator(2048).keyID("test-key").generate();
        var other = new RSAKeyGenerator(2048).keyID("test-key").generate();
        var publicKeys = new JWKSet(trusted.toPublicJWK());
        var identity = new GoogleGatewayIdentity("client", (selector, context) -> selector.select(publicKeys));
        Instant expiry = Instant.now().plusSeconds(120);
        assertEquals("12345", identity.verify(token(trusted, "client", "https://accounts.google.com", expiry)).subject());
        assertNull(identity.verify(token(other, "client", "https://accounts.google.com", expiry)));
        assertNull(identity.verify(token(trusted, "different-client", "https://accounts.google.com", expiry)));
        assertNull(identity.verify(token(trusted, "client", "https://example.com", expiry)));
        assertNull(identity.verify(token(trusted, "client", "https://accounts.google.com", Instant.EPOCH)));
    }

    private static String token(RSAKey key, String audience, String issuer, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("12345")
                .expirationTime(Date.from(expiry)).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
