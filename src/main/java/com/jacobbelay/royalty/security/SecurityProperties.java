package com.jacobbelay.royalty.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for sign-in, bound from {@code royalty.security.*} in application.yml (which in turn reads
 * environment variables, so nothing secret has to live in the repository).
 *
 * @param jwtSecret      HMAC key used to sign and verify tokens. HS256 needs at least 256 bits, so this must
 *                       be at least 32 characters; {@link SecurityConfig} refuses to start otherwise. Anyone
 *                       holding this key can mint valid tokens for any user, so in production it comes from
 *                       the {@code JWT_SECRET} environment variable and never from the default below.
 * @param tokenTtl        how long an issued token stays valid. Short, because a stateless token cannot be
 *                       revoked: there is no server-side session to delete, so expiry is the only off switch.
 * @param bootstrapAdminUsername first-run admin account, created by {@link AdminBootstrap} only when the
 *                       user table is empty, so a fresh database is usable without hand-written SQL.
 * @param bootstrapAdminPassword password for that account. Change it anywhere real; {@link AdminBootstrap}
 *                       logs a warning while the default is still in use.
 */
@ConfigurationProperties(prefix = "royalty.security")
public record SecurityProperties(
        String jwtSecret,
        Duration tokenTtl,
        String bootstrapAdminUsername,
        String bootstrapAdminPassword) {

    /** Shortest key HS256 accepts: 256 bits / 8 = 32 bytes. */
    public static final int MIN_SECRET_LENGTH = 32;

    /** Spelled out in application.yml too; kept here so {@link AdminBootstrap} can recognise it. */
    public static final String DEV_SECRET_MARKER = "dev-only";
}
