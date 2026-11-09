package com.jacobbelay.royalty.security;

import com.jacobbelay.royalty.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminBootstrap} seeds the first admin so a brand-new database can be signed into.
 *
 * <p>The behaviour that actually matters is the SECOND one below: it must do nothing once any user exists.
 * A bootstrap that ran unconditionally would reset the admin password back to the default on every restart,
 * quietly undoing the one security step an operator is most likely to have taken.
 */
class AdminBootstrapIT extends AbstractPostgresIT {

    @Autowired AdminBootstrap bootstrap;
    @Autowired AppUserRepository users;
    @Autowired SecurityProperties properties;

    @Test
    @DisplayName("seeds an admin when the user table is empty")
    void seedsFirstAdmin() {
        // @BeforeEach truncated app_user, so this is a fresh install.
        assertThat(users.anyUserExists()).isFalse();

        bootstrap.run(null);

        AppUser admin = users.findByUsername(properties.bootstrapAdminUsername()).orElseThrow();
        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.collaboratorId()).isNull();
        // Seeded through AuthService, so the password went through BCrypt like any other.
        assertThat(admin.passwordHash()).startsWith("$2a$");

        // And it can actually sign in.
        assertThat(auth.login(properties.bootstrapAdminUsername(), properties.bootstrapAdminPassword()).role())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("does nothing when a user already exists, so a changed password is never reset")
    void doesNotReseedOrOverwrite() {
        auth.createUser("existing-admin", "a-password-someone-chose", Role.ADMIN, null);
        String hashBefore = users.findByUsername("existing-admin").orElseThrow().passwordHash();

        bootstrap.run(null);
        bootstrap.run(null);        // and again: it must stay idempotent

        // The default admin was never created, and the real one was left alone.
        assertThat(users.findByUsername(properties.bootstrapAdminUsername())).isEmpty();
        assertThat(users.findByUsername("existing-admin").orElseThrow().passwordHash()).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("running twice on an empty database does not fail on the unique username")
    void secondRunIsASafeNoOp() {
        bootstrap.run(null);
        bootstrap.run(null);        // the guard, not the UNIQUE constraint, is what makes this safe

        assertThat(users.findByUsername(properties.bootstrapAdminUsername())).isPresent();
    }
}
