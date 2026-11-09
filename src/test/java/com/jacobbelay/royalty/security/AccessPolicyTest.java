package com.jacobbelay.royalty.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ownership rule, tested without a database or HTTP, because it is pure logic over an
 * {@link Authentication}. SecurityAccessIT then proves the same rule is actually wired to the endpoints —
 * a passing test here would mean nothing if @PreAuthorize were missing, and vice versa.
 */
class AccessPolicyTest {

    private final AccessPolicy policy = new AccessPolicy();

    /** Builds the kind of Authentication the JWT filter produces: a Jwt principal plus role authorities. */
    private static Authentication jwtAuth(String role, Long collaboratorId) {
        Jwt.Builder jwt = Jwt.withTokenValue("fake-token")
                .header("alg", "HS256")
                .subject("someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim(TokenService.ROLES_CLAIM, List.of(role));
        if (collaboratorId != null) {
            jwt.claim(TokenService.COLLABORATOR_CLAIM, collaboratorId);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    @DisplayName("an admin may act for any collaborator")
    void adminMayActForAnyone() {
        Authentication admin = jwtAuth("ADMIN", null);

        assertThat(policy.canActForCollaborator(admin, 1L)).isTrue();
        assertThat(policy.canActForCollaborator(admin, 42L)).isTrue();
    }

    @Test
    @DisplayName("an artist may act for the collaborator their token names")
    void artistMayActForOwnCollaborator() {
        assertThat(policy.canActForCollaborator(jwtAuth("ARTIST", 7L), 7L)).isTrue();
    }

    @Test
    @DisplayName("an artist may NOT act for someone else: the core ownership rule")
    void artistMayNotActForAnotherCollaborator() {
        Authentication ana = jwtAuth("ARTIST", 7L);

        assertThat(policy.canActForCollaborator(ana, 8L)).isFalse();
        assertThat(policy.canActForCollaborator(ana, 6L)).isFalse();
        assertThat(policy.canActForCollaborator(ana, 70L)).isFalse();   // not a prefix/suffix match either
    }

    @Test
    @DisplayName("an artist token with no collaborator claim can act for nobody")
    void artistWithoutClaimIsRefused() {
        // The database forbids such a user (V2 CHECK constraint), so this is the belt-and-braces case:
        // even if one appeared, it gets no access rather than defaulting to some collaborator.
        assertThat(policy.canActForCollaborator(jwtAuth("ARTIST", null), 1L)).isFalse();
    }

    @Test
    @DisplayName("a small id arriving as an Integer still matches")
    void integerClaimIsHandled() {
        // JSON has a single number type, so the decoder may hand back Integer for small ids and Long for
        // large ones. Reading the claim as a Number means id 7 is not silently refused.
        Jwt jwt = Jwt.withTokenValue("fake-token")
                .header("alg", "HS256")
                .subject("ana")
                .claim(TokenService.ROLES_CLAIM, List.of("ARTIST"))
                .claim(TokenService.COLLABORATOR_CLAIM, 7)              // Integer, not Long
                .build();
        Authentication auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ARTIST")));

        assertThat(policy.canActForCollaborator(auth, 7L)).isTrue();
    }

    @Test
    @DisplayName("no authentication at all is refused")
    void nullAuthenticationIsRefused() {
        assertThat(policy.canActForCollaborator(null, 1L)).isFalse();
    }

    @Test
    @DisplayName("an unauthenticated token is refused even if it claims the admin authority")
    void unauthenticatedIsRefused() {
        // TestingAuthenticationToken starts out not-authenticated. Checking isAuthenticated() first is what
        // stops a half-built authentication object from being treated as a signed-in admin.
        Authentication notAuthenticated =
                new TestingAuthenticationToken("someone", "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        notAuthenticated.setAuthenticated(false);

        assertThat(policy.canActForCollaborator(notAuthenticated, 1L)).isFalse();
    }

    @Test
    @DisplayName("an anonymous visitor is refused")
    void anonymousIsRefused() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(policy.canActForCollaborator(anonymous, 1L)).isFalse();
    }

    @Test
    @DisplayName("an authenticated non-JWT principal gets no ownership, only whatever its role allows")
    void nonJwtPrincipalHasNoOwnership() {
        // e.g. a form-login user. It has no collaboratorId claim to read, so ownership cannot be established.
        Authentication formLogin = new UsernamePasswordAuthenticationToken(
                "ana", "password", List.of(new SimpleGrantedAuthority("ROLE_ARTIST")));

        assertThat(policy.canActForCollaborator(formLogin, 7L)).isFalse();
    }

    @Test
    @DisplayName("an admin that is not a JWT principal is still an admin")
    void nonJwtAdminStillAllowed() {
        Authentication admin = new UsernamePasswordAuthenticationToken(
                "boss", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(policy.canActForCollaborator(admin, 7L)).isTrue();
    }
}
