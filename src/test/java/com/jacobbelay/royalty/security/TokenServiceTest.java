package com.jacobbelay.royalty.security;

import com.jacobbelay.royalty.support.TestTokens;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips tokens through the real signer and a real verifier, with no Spring context.
 *
 * <p>Signing and verifying with the same key proves the claims survive the trip; verifying with a DIFFERENT
 * key proves the signature is what makes a token trustworthy, which is the single most important property of
 * the whole scheme. If the second test ever passed, anyone could mint their own ADMIN token.
 */
class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-at-least-32-characters-long";

    private static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static TokenService tokenService(Duration ttl) {
        return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key(SECRET))),
                new SecurityProperties(SECRET, ttl, "admin", "admin"));
    }

    private static JwtDecoder decoder(String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static final AppUser ADMIN = new AppUser(1, "boss", "irrelevant-hash", Role.ADMIN, null);
    private static final AppUser ARTIST = new AppUser(2, "ana", "irrelevant-hash", Role.ARTIST, 7L);

    @Test
    @DisplayName("an admin token carries the username and the ADMIN role, and no collaborator")
    void adminTokenClaims() {
        String token = tokenService(Duration.ofHours(1)).issue(ADMIN);

        Jwt decoded = decoder(SECRET).decode(token);
        assertThat(decoded.getSubject()).isEqualTo("boss");
        assertThat(decoded.getClaimAsStringList(TokenService.ROLES_CLAIM)).containsExactly("ADMIN");
        // Read as a string, not via getIssuer(): that accessor coerces to a URL, and our issuer is a
        // plain name. The decoder's issuer validator compares it as a string too (see SecurityAccessIT).
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(TokenService.ISSUER);
        // Absent, not null: an admin is not a collaborator, so there is nothing to own.
        assertThat(decoded.hasClaim(TokenService.COLLABORATOR_CLAIM)).isFalse();
    }

    @Test
    @DisplayName("an artist token carries the collaborator it may act for")
    void artistTokenCarriesCollaboratorId() {
        String token = tokenService(Duration.ofHours(1)).issue(ARTIST);

        Jwt decoded = decoder(SECRET).decode(token);
        assertThat(decoded.getSubject()).isEqualTo("ana");
        assertThat(decoded.getClaimAsStringList(TokenService.ROLES_CLAIM)).containsExactly("ARTIST");
        assertThat(((Number) decoded.getClaim(TokenService.COLLABORATOR_CLAIM)).longValue()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a token signed with one key is rejected by a verifier holding another")
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = tokenService(Duration.ofHours(1)).issue(ADMIN);

        assertThatThrownBy(() -> decoder("a-different-secret-also-32-characters!!").decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("expiry is set from the configured TTL")
    void expirySetFromTtl() {
        Instant before = Instant.now();
        String token = tokenService(Duration.ofMinutes(15)).issue(ADMIN);

        Jwt decoded = decoder(SECRET).decode(token);
        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(decoded.getIssuedAt()).isNotNull();
        // Allow a couple of seconds of slack for a slow machine, but it must be ~15 minutes, not an hour.
        assertThat(decoded.getExpiresAt()).isBetween(before.plus(Duration.ofMinutes(14)),
                before.plus(Duration.ofMinutes(16)));
    }

    @Test
    @DisplayName("TokenService cannot even mint a token that is already expired")
    void negativeTtlIsRefusedAtSigningTime() {
        // Pleasant surprise worth pinning down: the encoder rejects expiresAt <= issuedAt, so a misconfigured
        // negative TTL fails loudly at sign-in instead of handing out tokens nothing will accept.
        assertThatThrownBy(() -> tokenService(Duration.ofHours(-2)).issue(ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an expired token is refused by the verifier")
    void expiredTokenIsRejected() {
        // Forged by TestTokens, because TokenService (correctly) will not produce one. The point is that
        // expiry is enforced by the VERIFIER, so a stale token stops working with no server-side state.
        String expired = new TestTokens(SECRET).expired("boss", "ADMIN");

        assertThatThrownBy(() -> decoder(SECRET).decode(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("tokenTtlSeconds reports what the login response promises the client")
    void ttlSecondsIsReported() {
        assertThat(tokenService(Duration.ofMinutes(30)).tokenTtlSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("the payload is readable by anyone: never put a secret in a claim")
    void payloadIsNotEncrypted() {
        String token = tokenService(Duration.ofHours(1)).issue(ARTIST);

        // Decode the middle segment with no key at all. This is not an attack; it is how JWTs work, and it
        // is why AppUser#passwordHash is never added as a claim.
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).contains("\"sub\":\"ana\"").contains("ARTIST");
        assertThat(payload).doesNotContain("irrelevant-hash");
    }

    @Test
    @DisplayName("two tokens for different users differ")
    void tokensAreUserSpecific() {
        TokenService tokens = tokenService(Duration.ofHours(1));

        assertThat(tokens.issue(ADMIN)).isNotEqualTo(tokens.issue(ARTIST));
    }

    @Test
    @DisplayName("roles come from the user record, so an artist cannot be issued an admin token")
    void rolesFollowTheUserRecord() {
        List<String> roles = decoder(SECRET).decode(tokenService(Duration.ofHours(1)).issue(ARTIST))
                .getClaimAsStringList(TokenService.ROLES_CLAIM);

        assertThat(roles).containsExactly("ARTIST").doesNotContain("ADMIN");
    }
}
