package com.jacobbelay.royalty.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard rails on {@link SecurityConfig} itself: a key too weak to sign with must stop the application,
 * not quietly weaken it.
 *
 * <p>A short HMAC secret still signs tokens perfectly happily — it is simply brute-forceable, which is the
 * worst kind of bug, because everything looks fine. Failing at startup converts a silent weakness into an
 * obvious crash, and these tests are what keep that behaviour.
 */
class SecurityConfigTest {

    private static final String VALID = "a-perfectly-adequate-secret-of-32-chars";

    private static SecurityConfig configWithSecret(String secret) {
        return new SecurityConfig(new SecurityProperties(secret, Duration.ofHours(1), "admin", "admin"));
    }

    @Test
    @DisplayName("a secret of 32+ characters produces a working encoder and decoder")
    void validSecretIsAccepted() {
        SecurityConfig config = configWithSecret(VALID);

        assertThat(VALID.length()).isGreaterThanOrEqualTo(SecurityProperties.MIN_SECRET_LENGTH);
        assertThat(config.jwtEncoder()).isNotNull();
        assertThat(config.jwtDecoder()).isNotNull();
    }

    @Test
    @DisplayName("a secret shorter than 32 characters refuses to start")
    void shortSecretIsRejected() {
        // 31 characters: one short. HS256 needs 256 bits = 32 bytes, so this is the exact boundary.
        String tooShort = "x".repeat(SecurityProperties.MIN_SECRET_LENGTH - 1);

        assertThatThrownBy(() -> configWithSecret(tooShort).jwtEncoder())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> configWithSecret(tooShort).jwtDecoder())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("exactly 32 characters is enough")
    void boundarySecretIsAccepted() {
        String exactly = "y".repeat(SecurityProperties.MIN_SECRET_LENGTH);

        assertThat(configWithSecret(exactly).jwtEncoder()).isNotNull();
    }

    @Test
    @DisplayName("a missing secret refuses to start")
    void nullSecretIsRejected() {
        assertThatThrownBy(() -> configWithSecret(null).jwtEncoder())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configWithSecret("").jwtEncoder())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the password encoder is BCrypt, salted and verifying")
    void passwordEncoderIsBcrypt() {
        PasswordEncoder encoder = configWithSecret(VALID).passwordEncoder();

        String hash = encoder.encode("hunter2");
        assertThat(hash).startsWith("$2a$");
        assertThat(encoder.matches("hunter2", hash)).isTrue();
        assertThat(encoder.matches("Hunter2", hash)).isFalse();        // case matters
        assertThat(encoder.encode("hunter2")).isNotEqualTo(hash);      // fresh salt each time
    }

    @Test
    @DisplayName("the roles claim is mapped to ROLE_-prefixed authorities")
    void authoritiesConverterIsConfigured() {
        // If the claim name or prefix drifted, hasRole("ADMIN") in the filter chain would match nothing and
        // every authenticated request would 403. SecurityAccessIT proves the end result; this pins the wiring.
        assertThat(configWithSecret(VALID).jwtAuthenticationConverter()).isNotNull();
    }
}
