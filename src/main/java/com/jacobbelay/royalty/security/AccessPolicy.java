package com.jacobbelay.royalty.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Answers one question: <b>may this request act for collaborator N?</b>
 *
 * <p>Roles alone cannot answer it. {@code hasRole("ARTIST")} is true for every artist, so it would let Ana
 * read Bob's balances — the classic "broken object level authorization" bug, and the most common serious
 * flaw in REST APIs. The fix is to compare the collaborator in the URL against the one the TOKEN is tied to
 * ({@link TokenService#COLLABORATOR_CLAIM}), which the client cannot forge without the signing key.
 *
 * <p>Registered as the bean named {@code access} so controllers can write
 * {@code @PreAuthorize("@access.canActForCollaborator(authentication, #id)")}. Keeping the rule in one bean
 * rather than inline in each controller means there is a single place to read, test and change it — see
 * AccessPolicyTest and api/SecurityAccessIT.
 */
@Component("access")
public class AccessPolicy {

    static final String ADMIN_AUTHORITY = "ROLE_" + Role.ADMIN;

    /**
     * @return true for any ADMIN, or for an ARTIST whose token names exactly this collaborator.
     *         False for anonymous or unauthenticated requests, and for a token with no collaborator claim.
     */
    public boolean canActForCollaborator(Authentication authentication, long collaboratorId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAdminAuthority(authentication)) {
            return true;       // staff may act for anyone
        }
        Long linked = linkedCollaboratorId(authentication);
        return linked != null && linked == collaboratorId;
    }

    private static boolean hasAdminAuthority(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code collaboratorId} claim, or null when the principal is not a JWT or carries no such
     * claim. JSON has one number type, so the claim can come back as Integer or Long depending on its size;
     * treating it as {@link Number} avoids a ClassCastException on small ids.
     */
    private static Long linkedCollaboratorId(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        Object claim = jwt.getClaim(TokenService.COLLABORATOR_CLAIM);
        return claim instanceof Number number ? number.longValue() : null;
    }
}
