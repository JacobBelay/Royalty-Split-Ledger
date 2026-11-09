package com.jacobbelay.royalty.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * THE AUTHORIZATION RULES. One file that answers "who may call what", plus the crypto wiring that makes a
 * token trustworthy.
 *
 * <p><b>The request path</b>: a request arrives with {@code Authorization: Bearer <token>} →
 * {@link #jwtDecoder} checks the signature, the issuer and the expiry → {@link #jwtAuthenticationConverter}
 * turns the {@code roles} claim into Spring authorities → the rules in {@link #filterChain} allow or refuse
 * it → for the two per-collaborator endpoints, {@link AccessPolicy} also checks ownership. Anything refused
 * is written as problem JSON by {@link ProblemSecurityHandler}.
 *
 * <p>Read next: {@link TokenService} (the other half of the crypto) and {@link AccessPolicy}.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
// Switches on @PreAuthorize. Without it those annotations are silently ignored — which would leave the
// ownership checks in CollaboratorController looking present but doing nothing, so it is load-bearing.
@EnableMethodSecurity
public class SecurityConfig {

    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandler problemHandler)
            throws Exception {
        http
                // No cookies are used, so there is no cookie for a malicious site to ride on and CSRF
                // protection would only break legitimate clients. A bearer token has to be attached
                // deliberately by the caller, which is the property CSRF tokens exist to recreate.
                .csrf(csrf -> csrf.disable())

                // Never create an HttpSession: every request must prove itself with its token. This is what
                // "stateless" means in practice, and it is why any instance can serve any request.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Turn off the defaults we are replacing, so there is no second way in.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandler)    // 401
                        .accessDeniedHandler(problemHandler))        // 403

                .oauth2ResourceServer(oauth2 -> oauth2
                        // Needed as well as the one above, and easy to miss: the bearer-token filter
                        // installs its OWN entry point for a token it cannot accept. Without this line a
                        // MISSING token answers problem JSON (via exceptionHandling) while an EXPIRED or
                        // badly signed one answers an empty body — the same 401 in two different shapes.
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))

                .authorizeHttpRequests(requests -> requests
                        // --- open ---
                        // Sign-in must be reachable without a token, or nobody could ever get one.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // --- ADMIN only: everything that moves money or changes the setup ---
                        .requestMatchers(HttpMethod.POST, "/api/auth/users").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/collaborators").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/releases").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/statements").hasRole(Role.ADMIN.name())
                        // '*' matches one path segment, so this covers /api/collaborators/{id}/advances
                        // without also matching anything deeper.
                        .requestMatchers(HttpMethod.POST, "/api/collaborators/*/advances").hasRole(Role.ADMIN.name())
                        // The trial balance is the whole company's position, not one artist's.
                        .requestMatchers(HttpMethod.GET, "/api/ledger/**").hasRole(Role.ADMIN.name())

                        // --- ADMIN or the ARTIST who owns the row ---
                        // The role is checked here; WHICH collaborator is checked by @PreAuthorize on the
                        // controller methods, because a URL pattern cannot compare {id} to a token claim.
                        .requestMatchers(HttpMethod.GET, "/api/collaborators/*/balances")
                        .hasAnyRole(Role.ADMIN.name(), Role.ARTIST.name())
                        .requestMatchers(HttpMethod.POST, "/api/collaborators/*/payouts")
                        .hasAnyRole(Role.ADMIN.name(), Role.ARTIST.name())

                        // --- default: refuse ---
                        // denyAll, not authenticated(). A new endpoint is then locked until someone adds a
                        // rule for it on purpose: forgetting to think about access fails closed, which is the
                        // only safe direction for a service that moves money. (Side effect: an unknown path
                        // answers 403 rather than 404, which also avoids confirming what does exist.)
                        .anyRequest().denyAll());

        return http.build();
    }

    /**
     * BCrypt: deliberately slow (~100ms) and salted per password, so a stolen {@code password_hash} column
     * cannot be cracked at speed or matched against a precomputed table. Never swap this for a fast hash
     * like SHA-256 — fast is the wrong property for a password.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Signs tokens. Same key as {@link #jwtDecoder}, which is what makes HMAC symmetric. */
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    /**
     * Verifies tokens on every request: signature, expiry, and issuer.
     *
     * <p>{@code macAlgorithm(HS256)} pins the algorithm instead of believing the token's own header. That
     * matters: historically, JWT libraries that trusted the header could be handed {@code "alg":"none"} and
     * would accept an unsigned token. Pinning it removes the question.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Default validator checks expiry (with a small clock skew allowance); the issuer check rejects an
        // otherwise-valid token minted by some other system that happens to share a key.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));
        return decoder;
    }

    /** Maps the {@code roles} claim (["ADMIN"]) to the authority Spring compares ({@code ROLE_ADMIN}). */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Turns the configured secret into an HMAC key, refusing to start if it is too short. Failing at startup
     * is the point: a 10-character secret would still sign tokens happily while being brute-forceable, so a
     * loud crash now beats a quiet weakness in production.
     */
    private SecretKeySpec secretKey() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.length() < SecurityProperties.MIN_SECRET_LENGTH) {
            throw new IllegalStateException("royalty.security.jwt-secret (env JWT_SECRET) must be at least "
                    + SecurityProperties.MIN_SECRET_LENGTH + " characters for HS256");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
