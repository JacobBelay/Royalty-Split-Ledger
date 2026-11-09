-- =====================================================================================================
-- V2: APPLICATION USERS (authentication + roles)
-- What: the accounts that can sign in, their BCrypt password hash, and their role.
-- Why a table and not users in a config file: an ARTIST row has to POINT AT a collaborator, because
--   "an artist may read only their own balances" is a rule about data ownership, not just a role name.
--   security/AccessPolicy.java reads that link on every request.
-- Read next: security/AppUserRepository.java, then security/SecurityConfig.java.
-- Flyway applies this after V1. Never edit an applied migration; add V3__... instead.
-- =====================================================================================================

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,

    -- The sign-in name. UNIQUE is what makes "one account per name" true even if two admins create the
    -- same user at the same moment: the second INSERT fails instead of producing a duplicate.
    username        TEXT        NOT NULL UNIQUE,

    -- BCrypt output, e.g. $2a$10$... (60 chars). We never store the password itself: a database leak
    -- must not hand over anyone's password. See security/SecurityConfig#passwordEncoder.
    password_hash   TEXT        NOT NULL,

    role            TEXT        NOT NULL CHECK (role IN ('ADMIN', 'ARTIST')),

    -- Which collaborator this user IS. NULL for ADMIN (an admin is staff, not a royalty earner).
    collaborator_id BIGINT      REFERENCES collaborator(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The same "this pairing must be honest" trick V1 uses for ('CASH', NULL): an ARTIST without a
    -- collaborator could not be checked for ownership, and an ADMIN with one would be ambiguous.
    -- Writing it as an equality of two booleans rejects BOTH mistakes in one line.
    CONSTRAINT app_user_artist_has_collaborator
        CHECK ((role = 'ARTIST') = (collaborator_id IS NOT NULL))
);

-- AccessPolicy looks a user up by username on sign-in; the UNIQUE constraint above already indexes that.
-- This second index serves the reverse question ("which user owns collaborator 7").
CREATE INDEX app_user_collaborator_idx ON app_user (collaborator_id);
