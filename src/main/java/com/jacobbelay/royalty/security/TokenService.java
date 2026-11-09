package com.jacobbelay.royalty.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mints the signed token a client sends back as {@code Authorization: Bearer <token>}.
 *
 * <p><b>What a JWT actually is</b>: three base64 chunks joined by dots — a header saying how it was signed,
 * a payload of claims (who, what roles, when it expires), and a signature over both. The payload is
 * <em>readable by anyone</em>; it is not encrypted. The signature is what matters: altering a single
 * character of the payload invalidates it, so a client cannot promote itself to ADMIN by editing the token.
 * Therefore: never put a secret in a claim, and never trust a claim without verifying the signature
 * (which {@link SecurityConfig}'s decoder does on every single request).
 *
 * <p><b>Why stateless</b>: the server stores nothing. Any instance with the same key can verify any token,
 * which is what lets the service scale horizontally. The cost is that a token cannot be revoked before it
 * expires, which is why {@link SecurityProperties#tokenTtl} is short.
 *
 * <p>Read next: {@link AuthService} (checks the password, then calls this) and {@link AccessPolicy}
 * (reads the {@code collaboratorId} claim this writes).
 */
@Service
public class TokenService {

    /** Identifies who issued the token; the decoder in {@link SecurityConfig} requires this exact value. */
    public static final String ISSUER = "royalty-split-ledger";

    /** Claim holding role names. {@link SecurityConfig} maps each to a {@code ROLE_}-prefixed authority. */
    public static final String ROLES_CLAIM = "roles";

    /** Claim linking an ARTIST token to the collaborator it may act for. Absent on ADMIN tokens. */
    public static final String COLLABORATOR_CLAIM = "collaboratorId";

    private final JwtEncoder encoder;
    private final Duration tokenTtl;

    public TokenService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.tokenTtl = properties.tokenTtl();
    }

    /** @return the compact token string, already signed. */
    public String issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .subject(user.username())                      // "sub": who this token speaks for
                .claim(ROLES_CLAIM, List.of(user.role().name()));

        // Only ARTIST tokens carry this. An ADMIN is not a collaborator, and leaving the claim out is
        // clearer than a null: AccessPolicy then simply finds no ownership to check.
        if (user.collaboratorId() != null) {
            claims.claim(COLLABORATOR_CLAIM, user.collaboratorId());
        }

        // The header must name the algorithm explicitly: the encoder would otherwise assume an RSA default
        // and fail, since our key is a shared secret (HMAC), not a key pair.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /** Exposed so the login response can tell the client how long the token is good for. */
    public long tokenTtlSeconds() {
        return tokenTtl.toSeconds();
    }
}
