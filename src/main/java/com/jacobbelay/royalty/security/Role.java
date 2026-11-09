package com.jacobbelay.royalty.security;

/**
 * The two kinds of account. Kept as an enum rather than free text so a typo ("ARIST") is a compile error
 * here and a CHECK-constraint violation in the database (V2__app_user.sql), never a silently powerless user.
 *
 * <p>Spring Security spells roles with a {@code ROLE_} prefix (so {@code hasRole("ADMIN")} looks for the
 * authority {@code ROLE_ADMIN}). We store the bare name and let
 * {@link SecurityConfig#jwtAuthenticationConverter()} add the prefix when it reads the token, so the
 * database and the JWT stay free of framework spelling.
 *
 * <p>Who may do what is declared in {@link SecurityConfig}; ownership ("your own balances") is decided by
 * {@link AccessPolicy}.
 */
public enum Role {

    /** Staff. Creates collaborators and users, imports statements, issues advances, reads the ledger. */
    ADMIN,

    /** A royalty earner. May read their own balances and request their own payout, and nothing else. */
    ARTIST
}
