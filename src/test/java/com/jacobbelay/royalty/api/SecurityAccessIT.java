package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.collaborator.Collaborator;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.security.SecurityProperties;
import com.jacobbelay.royalty.split.Share;
import com.jacobbelay.royalty.statement.StatementImportCommand;
import com.jacobbelay.royalty.statement.StatementImportService;
import com.jacobbelay.royalty.statement.StatementLine;
import com.jacobbelay.royalty.support.AbstractPostgresIT;
import com.jacobbelay.royalty.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE ACCESS MATRIX: who may call what, proved through real HTTP with real signed tokens.
 *
 * <p>Three questions, each a group of tests below:
 * <ol>
 *   <li><b>No token</b> → 401 everywhere. Nothing is readable or writable anonymously.</li>
 *   <li><b>A bad token</b> → 401. Expired, foreign-signed, wrong issuer and malformed are all refused, so a
 *       token is only worth something because of its signature and its claims.</li>
 *   <li><b>A valid ARTIST token</b> → 403 on everything that is not theirs. This is the group that matters
 *       most: the caller IS authenticated, so only authorization stands between them and someone else's
 *       money.</li>
 * </ol>
 *
 * <p>AccessPolicyTest covers the same ownership rule as pure logic. Both are needed: that test would still
 * pass if {@code @PreAuthorize} were deleted from the controller, and these tests are what would catch it.
 */
class SecurityAccessIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired SecurityProperties properties;
    @Autowired StatementImportService statementImports;

    private long ana;
    private long ben;
    private String anaToken;
    private String benToken;

    /**
     * Ana ends up with 100.00 payable and a threshold of 25.00, so a payout she is allowed to request
     * succeeds outright (201). That way an allowed call is distinguishable from a merely-not-403 one.
     */
    @BeforeEach
    void seedTwoArtistsAndSomeMoney() {
        Collaborator anaRow = collaborator("Ana", "25.00");
        Collaborator benRow = collaborator("Ben", "25.00");
        ana = anaRow.id();
        ben = benRow.id();
        long song = release("Night Drive", new Share(ana, new BigDecimal("100")));

        // Posted through the service rather than HTTP: this test is about access control, so the setup must
        // not depend on the very endpoint whose access it is about to probe.
        statementImports.importStatement(new StatementImportCommand("DistroKid", "2026-08",
                List.of(new StatementLine(song, Money.of("100.00")))));

        anaToken = artistToken(ana);
        benToken = artistToken(ben);
    }

    // ---------- 1. No token at all ----------------------------------------------------------------------

    @Test
    @DisplayName("every endpoint refuses an anonymous request with 401")
    void anonymousRequestsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/collaborators/" + ana + "/balances")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ledger/trial-balance")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/collaborators").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sneaky\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"shares\":[]}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/statements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"x\",\"externalRef\":\"y\",\"lines\":[]}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/collaborators/" + ana + "/advances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10.00}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/collaborators/" + ana + "/payouts").header("Idempotency-Key", "k"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"password1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a 401 is problem+json and says how to authenticate")
    void unauthorizedBodyIsProblemJson() throws Exception {
        mvc.perform(get("/api/ledger/trial-balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value(containsString("/api/auth/login")))
                // The standard header telling a client WHICH scheme to use.
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    @DisplayName("an unknown path is refused rather than confirmed, because the default rule is denyAll")
    void unknownPathsAreDeniedByDefault() throws Exception {
        mvc.perform(get("/api/secret-admin-panel")).andExpect(status().isUnauthorized());
        mvc.perform(bearing(get("/api/secret-admin-panel"), adminToken())).andExpect(status().isForbidden());
    }

    // ---------- 2. Bad tokens ---------------------------------------------------------------------------

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenIsRefused() throws Exception {
        String expired = new TestTokens(properties.jwtSecret()).expired("it-admin", "ADMIN");

        mvc.perform(bearing(get("/api/ledger/trial-balance"), expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with someone else's key is refused, however perfect its claims")
    void foreignSignedTokenIsRefused() throws Exception {
        // This is the attack the whole scheme exists to stop: a self-minted ADMIN token.
        String forged = new TestTokens(TestTokens.FOREIGN_SECRET).admin("attacker");

        mvc.perform(bearing(get("/api/ledger/trial-balance"), forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a forged artist token cannot read the balances it names")
    void foreignSignedArtistTokenIsRefused() throws Exception {
        String forged = new TestTokens(TestTokens.FOREIGN_SECRET).artist("attacker", ana);

        mvc.perform(bearing(get("/api/collaborators/" + ana + "/balances"), forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a correctly signed token from a different issuer is refused")
    void wrongIssuerIsRefused() throws Exception {
        // Proves the issuer validator in SecurityConfig#jwtDecoder is actually live: this token's signature
        // verifies, so only the issuer check can be rejecting it.
        String otherService = new TestTokens(properties.jwtSecret()).wrongIssuer("it-admin", "ADMIN");

        mvc.perform(bearing(get("/api/ledger/trial-balance"), otherService))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a REJECTED token gets the same problem+json shape as a missing one")
    void rejectedTokenBodyIsAlsoProblemJson() throws Exception {
        // Regression test. The bearer-token filter installs its own entry point, so until
        // SecurityConfig passed the handler to oauth2ResourceServer as well, a missing token answered
        // problem JSON while an expired or forged one answered an empty body: one status, two shapes.
        String expired = new TestTokens(properties.jwtSecret()).expired("it-admin", "ADMIN");
        String forged = new TestTokens(TestTokens.FOREIGN_SECRET).admin("attacker");

        for (String badToken : new String[]{expired, forged, "not-a-jwt"}) {
            mvc.perform(bearing(get("/api/ledger/trial-balance"), badToken))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").value(containsString("/api/auth/login")));
        }
    }

    @Test
    @DisplayName("garbage in the Authorization header is refused, not crashed on")
    void malformedTokensAreRefused() throws Exception {
        mvc.perform(bearing(get("/api/ledger/trial-balance"), "not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearing(get("/api/ledger/trial-balance"), "aaa.bbb.ccc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearing(get("/api/ledger/trial-balance"), ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token whose payload was edited is refused")
    void tamperedPayloadIsRefused() throws Exception {
        // Take a REAL artist token and swap one character of its payload. The signature no longer matches,
        // which is exactly why claims can be trusted even though anyone can read them.
        String[] parts = anaToken.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 2)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        mvc.perform(bearing(get("/api/collaborators/" + ana + "/balances"), tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid token carrying no roles authenticates but may do nothing")
    void tokenWithoutRolesIsForbidden() throws Exception {
        String roleless = new TestTokens(properties.jwtSecret()).noRoles("nobody");

        // 403, not 401: we know who they are, they simply have no permissions. The distinction tells a
        // client whether signing in again would help.
        mvc.perform(bearing(get("/api/ledger/trial-balance"), roleless))
                .andExpect(status().isForbidden());
    }

    // ---------- 3. A real artist, reaching where they should not -----------------------------------------

    @Test
    @DisplayName("an artist reads their own balances")
    void artistReadsOwnBalances() throws Exception {
        mvc.perform(bearing(get("/api/collaborators/" + ana + "/balances"), anaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payable").value(100.00));
    }

    @Test
    @DisplayName("an artist may NOT read another artist's balances")
    void artistCannotReadAnotherArtistsBalances() throws Exception {
        // The headline rule. Ben is authenticated and has the ARTIST role, so the role check passes; only
        // the ownership check in @PreAuthorize stops him reading Ana's earnings.
        mvc.perform(bearing(get("/api/collaborators/" + ana + "/balances"), benToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("an artist may request their own payout")
    void artistRequestsOwnPayout() throws Exception {
        mvc.perform(bearing(post("/api/collaborators/" + ana + "/payouts"), anaToken)
                        .header("Idempotency-Key", "ana-own-payout"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(100.00));
    }

    @Test
    @DisplayName("an artist may NOT request a payout to another artist")
    void artistCannotRequestAnotherArtistsPayout() throws Exception {
        mvc.perform(bearing(post("/api/collaborators/" + ana + "/payouts"), benToken)
                        .header("Idempotency-Key", "ben-steals-anas-money"))
                .andExpect(status().isForbidden());

        // And nothing happened: a 403 must leave the ledger untouched, not merely hide the response.
        assertThat(payable(ana)).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("an artist may not create collaborators, releases, statements, advances or users")
    void artistCannotPerformAdminActions() throws Exception {
        mvc.perform(bearing(post("/api/collaborators"), anaToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ghost\"}")).andExpect(status().isForbidden());

        mvc.perform(bearing(post("/api/releases"), anaToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Mine\",\"shares\":[{\"collaboratorId\":%d,\"percent\":100}]}".formatted(ana)))
                .andExpect(status().isForbidden());

        mvc.perform(bearing(post("/api/statements"), anaToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"Fake\",\"externalRef\":\"made-up\",\"lines\":[]}"))
                .andExpect(status().isForbidden());

        // Issuing yourself an advance would be paying yourself money you have not earned.
        mvc.perform(bearing(post("/api/collaborators/" + ana + "/advances"), anaToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000.00}"))
                .andExpect(status().isForbidden());

        // Creating users would let an artist mint themselves an ADMIN account: the privilege-escalation path.
        mvc.perform(bearing(post("/api/auth/users"), anaToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ana-admin\",\"password\":\"password1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an artist may not read the company-wide trial balance")
    void artistCannotReadTrialBalance() throws Exception {
        // It is everyone's money summed together, which is not an artist's business.
        mvc.perform(bearing(get("/api/ledger/trial-balance"), anaToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin may do all of it")
    void adminMayDoEverything() throws Exception {
        String admin = adminToken();

        mvc.perform(bearing(get("/api/collaborators/" + ana + "/balances"), admin)).andExpect(status().isOk());
        mvc.perform(bearing(get("/api/collaborators/" + ben + "/balances"), admin)).andExpect(status().isOk());
        mvc.perform(bearing(get("/api/ledger/trial-balance"), admin)).andExpect(status().isOk());
        mvc.perform(bearing(post("/api/collaborators"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cleo\"}")).andExpect(status().isCreated());
        mvc.perform(bearing(post("/api/collaborators/" + ben + "/advances"), admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isCreated());
    }
}
