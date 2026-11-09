package com.jacobbelay.royalty.support;

import com.jacobbelay.royalty.collaborator.Collaborator;
import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.ledger.Account;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.release.ReleaseService;
import com.jacobbelay.royalty.security.AuthService;
import com.jacobbelay.royalty.security.Role;
import com.jacobbelay.royalty.split.Share;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for tests that need the real database: the triggers, unique constraints and row locks can't be
 * faked, so these tests run the full Spring app against PostgreSQL 16.
 *
 * <p><b>Where the database comes from</b>
 * <ul>
 *   <li>Default: <b>Testcontainers</b> starts {@code postgres:16-alpine} in Docker once for the whole test run
 *       (the "singleton container" pattern: started in a static block, shared by every subclass, removed
 *       automatically when the JVM exits). Needs Docker Desktop running.</li>
 *   <li>If env var {@code TEST_DATABASE_URL} is set (e.g. {@code jdbc:postgresql://localhost:5432/royalties_test}),
 *       that database is used instead. Handy when Docker isn't available. Also reads
 *       {@code TEST_DATABASE_USER} / {@code TEST_DATABASE_PASSWORD} (default royalties/royalties).</li>
 * </ul>
 * {@link DynamicPropertySource} injects the chosen URL into Spring before the context starts; Flyway then
 * applies V1__ledger_schema.sql to it.
 *
 * <p><b>Isolation</b>: every test starts from empty tables ({@link #resetDatabase}). Tests are NOT wrapped in
 * a rolled-back transaction (a common Spring shortcut) because the deferred balance trigger only fires at
 * COMMIT and the concurrency tests need real commits between threads.
 *
 * <p>After every test, {@link #ledgerMustStillBalance} checks the trial balance, so every scenario also
 * proves the core invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractPostgresIT {

    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_URL == null) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> envOr("TEST_DATABASE_USER", "royalties"));
            registry.add("spring.datasource.password", () -> envOr("TEST_DATABASE_PASSWORD", "royalties"));
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @Autowired protected JdbcClient jdbc;
    @Autowired protected LedgerRepository ledger;
    @Autowired protected CollaboratorRepository collaborators;
    @Autowired protected ReleaseService releases;
    @Autowired protected AuthService auth;

    /** Cached per test so repeated requests in one scenario don't re-run BCrypt (~100ms each). */
    private String adminToken;

    /**
     * TRUNCATE is allowed even though UPDATE/DELETE on the ledger are blocked: the append-only triggers only
     * cover row-level UPDATE and DELETE (see V1__ledger_schema.sql). RESTART IDENTITY resets ids to 1.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE app_user, journal_line, journal_entry, payout, advance, statement_import, release_share, release, collaborator RESTART IDENTITY CASCADE")
                .update();
        adminToken = null;      // the user it belonged to was just deleted
    }

    @AfterEach
    void ledgerMustStillBalance() {
        assertThat(ledger.trialBalance().balanced()).as("total debits == total credits").isTrue();
    }

    // ---------- Small helpers so tests read like the scenario they describe ------------------------------

    protected Collaborator collaborator(String name, String threshold) {
        return collaborators.create(name, Money.of(threshold));
    }

    protected long release(String title, Share... shares) {
        return releases.createRelease(title, List.of(shares));
    }

    protected Money payable(long collaboratorId) {
        return ledger.balance(Account.payable(collaboratorId));
    }

    protected Money advanceOutstanding(long collaboratorId) {
        return ledger.balance(Account.advanceReceivable(collaboratorId));
    }

    protected Money cash() {
        return ledger.balance(Account.cash());
    }

    // ---------- Authentication helpers -------------------------------------------------------------------
    // These mint REAL tokens through the real AuthService and TokenService rather than faking a security
    // context with @WithMockUser. Two reasons: the signing and verification code is then covered by the same
    // tests, and an ARTIST token carries the collaboratorId claim that security/AccessPolicy actually reads —
    // a mocked principal has no such claim, so the ownership rules would never really be exercised.

    /** Token for a fresh ADMIN. Created once per test, since @BeforeEach empties app_user. */
    protected String adminToken() {
        if (adminToken == null) {
            auth.createUser("it-admin", "it-admin-password", Role.ADMIN, null);
            adminToken = auth.login("it-admin", "it-admin-password").token();
        }
        return adminToken;
    }

    /** Token for an ARTIST tied to {@code collaboratorId} — i.e. who may act only for that collaborator. */
    protected String artistToken(long collaboratorId) {
        String username = "it-artist-" + collaboratorId;
        auth.createUser(username, "it-artist-password", Role.ARTIST, collaboratorId);
        return auth.login(username, "it-artist-password").token();
    }

    /** Attaches a bearer token, the way a real client would. */
    protected static MockHttpServletRequestBuilder bearing(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
