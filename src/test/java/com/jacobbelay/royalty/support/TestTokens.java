package com.jacobbelay.royalty.support;

import com.jacobbelay.royalty.security.TokenService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mints tokens a well-behaved client would never send: expired, wrongly signed, wrong issuer, no roles.
 *
 * <p>Why this exists: security/TokenService.java can only produce VALID tokens, so on its own it cannot show
 * that the decoder rejects anything. Proving a door is locked needs a key that should not fit. Each method
 * here forges one specific defect so a test can assert the API answers 401 rather than trusting it.
 *
 * <p>Construct with the application's real secret to forge "valid signature, wrong contents", or with any
 * other 32+ character secret to forge "right contents, wrong signature".
 */
public final class TestTokens {

    /** A syntactically fine HS256 key that the application does NOT use. */
    public static final String FOREIGN_SECRET = "a-totally-different-secret-key-32+chars";

    private final JwtEncoder encoder;

    public TestTokens(String secret) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** A token that is correct in every way except that it expired two hours ago. */
    public String expired(String username, String role) {
        Instant longAgo = Instant.now().minus(Duration.ofHours(3));
        // Two hours is well past the decoder's 60-second clock-skew allowance, so this cannot pass by luck.
        return mint(TokenService.ISSUER, longAgo, longAgo.plus(Duration.ofHours(1)), username, List.of(role), null);
    }

    /** Correct in every way except that some other system issued it. */
    public String wrongIssuer(String username, String role) {
        return mint("some-other-service", Instant.now(), Instant.now().plus(Duration.ofHours(1)),
                username, List.of(role), null);
    }

    /** Well-formed and unexpired, but with no roles — so it authenticates and then fails every rule. */
    public String noRoles(String username) {
        return mint(TokenService.ISSUER, Instant.now(), Instant.now().plus(Duration.ofHours(1)),
                username, List.of(), null);
    }

    /** A valid ARTIST token for {@code collaboratorId}; used with FOREIGN_SECRET to forge a bad signature. */
    public String artist(String username, long collaboratorId) {
        return mint(TokenService.ISSUER, Instant.now(), Instant.now().plus(Duration.ofHours(1)),
                username, List.of("ARTIST"), collaboratorId);
    }

    /** A valid-looking ADMIN token; used with FOREIGN_SECRET to prove a forged admin is refused. */
    public String admin(String username) {
        return mint(TokenService.ISSUER, Instant.now(), Instant.now().plus(Duration.ofHours(1)),
                username, List.of("ADMIN"), null);
    }

    private String mint(String issuer, Instant issuedAt, Instant expiresAt, String subject,
                        List<String> roles, Long collaboratorId) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(subject)
                .claim(TokenService.ROLES_CLAIM, roles);
        if (collaboratorId != null) {
            claims.claim(TokenService.COLLABORATOR_CLAIM, collaboratorId);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
