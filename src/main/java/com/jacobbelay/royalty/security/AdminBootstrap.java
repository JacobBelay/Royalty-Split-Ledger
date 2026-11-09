package com.jacobbelay.royalty.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN on an empty database, so a fresh install can be signed into without writing SQL
 * by hand. Runs once at startup, after Flyway has created the tables.
 *
 * <p>Guarded by {@link AppUserRepository#anyUserExists()}: as soon as there is any user, this does nothing.
 * That makes it safe on every restart and means it can never overwrite a password you changed.
 *
 * <p>It shouts if the default password is still in use. A seeded default credential is convenient and also
 * exactly how real systems get breached, so the warning names the environment variable that fixes it.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Must match the default in application.yml for the warning below to be accurate. */
    static final String DEFAULT_PASSWORD = "admin";

    private final AppUserRepository users;
    private final AuthService auth;
    private final SecurityProperties properties;

    public AdminBootstrap(AppUserRepository users, AuthService auth, SecurityProperties properties) {
        this.users = users;
        this.auth = auth;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.anyUserExists()) {
            return;
        }
        auth.createUser(properties.bootstrapAdminUsername(), properties.bootstrapAdminPassword(),
                Role.ADMIN, null);
        log.info("Created first admin user '{}' because app_user was empty", properties.bootstrapAdminUsername());

        if (DEFAULT_PASSWORD.equals(properties.bootstrapAdminPassword())) {
            log.warn("SECURITY: admin account is using the default password. "
                    + "Set BOOTSTRAP_ADMIN_PASSWORD before exposing this service to a network.");
        }
    }
}
