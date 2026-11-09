package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.collaborator.Collaborator;
import com.jacobbelay.royalty.security.AppUser;
import com.jacobbelay.royalty.security.AppUserRepository;
import com.jacobbelay.royalty.security.Role;
import com.jacobbelay.royalty.support.AbstractPostgresIT;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in and account creation over real HTTP: {@code POST /api/auth/login} and
 * {@code POST /api/auth/users}.
 *
 * <p>SecurityAccessIT asks what a token can do; this asks how one is obtained, what a failed attempt reveals
 * (as little as possible), and which accounts may exist at all.
 */
class AuthIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository appUsers;

    private static final String PASSWORD = "correct-horse-battery";

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    private ResultActions createUser(String token, String body) throws Exception {
        return mvc.perform(bearing(post("/api/auth/users"), token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- Signing in -------------------------------------------------------------------------------

    @Test
    @DisplayName("correct credentials return a usable bearer token")
    void loginReturnsWorkingToken() throws Exception {
        auth.createUser("boss", PASSWORD, Role.ADMIN, null);

        String body = login("boss", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.expiresInSeconds").value(greaterThan(0)))
                .andExpect(jsonPath("$.collaboratorId").doesNotExist())   // an admin is not a collaborator
                .andReturn().getResponse().getContentAsString();

        // The real test of a token is whether it opens a door, so use it.
        String token = JsonPath.read(body, "$.token");
        mvc.perform(bearing(get("/api/ledger/trial-balance"), token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an artist's login tells them which collaborator they are")
    void artistLoginReportsCollaborator() throws Exception {
        Collaborator ana = collaborator("Ana", "25.00");
        auth.createUser("ana", PASSWORD, Role.ARTIST, ana.id());

        login("ana", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ARTIST"))
                .andExpect(jsonPath("$.collaboratorId").value(ana.id()));
    }

    @Test
    @DisplayName("the response never contains the password or its hash")
    void loginResponseLeaksNothing() throws Exception {
        auth.createUser("boss", PASSWORD, Role.ADMIN, null);
        AppUser stored = appUsers.findByUsername("boss").orElseThrow();

        String body = login("boss", PASSWORD).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain(stored.passwordHash());
    }

    @Test
    @DisplayName("a wrong password is 401, and an unknown user is the SAME 401")
    void badCredentialsAreIndistinguishable() throws Exception {
        auth.createUser("boss", PASSWORD, Role.ADMIN, null);

        String wrongPassword = login("boss", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = login("nobody-at-all", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical bodies. A different message for each would let anyone enumerate valid usernames, which
        // is why AuthService throws the same exception for both (and burns the same CPU doing it).
        assertThat(wrongPassword).isEqualTo(noSuchUser);
        assertThat(wrongPassword).contains("invalid username or password");
    }

    @Test
    @DisplayName("a login missing fields is a 400, not a 401")
    void malformedLoginIsBadRequest() throws Exception {
        // Shape problems are the client's bug; credential problems are the user's. Different status codes.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        login("", PASSWORD).andExpect(status().isBadRequest());
        login("boss", "").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("passwords are stored as BCrypt hashes, never as text")
    void passwordsAreHashed() throws Exception {
        auth.createUser("boss", PASSWORD, Role.ADMIN, null);

        String hash = appUsers.findByUsername("boss").orElseThrow().passwordHash();

        assertThat(hash).startsWith("$2a$").doesNotContain(PASSWORD);
        assertThat(hash).hasSize(60);               // BCrypt's fixed output length
    }

    @Test
    @DisplayName("the same password for two users produces different hashes")
    void hashesAreSalted() throws Exception {
        Collaborator ana = collaborator("Ana", "25.00");
        auth.createUser("boss", PASSWORD, Role.ADMIN, null);
        auth.createUser("ana", PASSWORD, Role.ARTIST, ana.id());

        String bossHash = appUsers.findByUsername("boss").orElseThrow().passwordHash();
        String anaHash = appUsers.findByUsername("ana").orElseThrow().passwordHash();

        // Per-password random salt. Without it, identical passwords would be visibly identical in the
        // database and one cracked hash would unlock every account that shared it.
        assertThat(bossHash).isNotEqualTo(anaHash);
    }

    // ---------- Creating accounts ------------------------------------------------------------------------

    @Test
    @DisplayName("an admin creates an artist account linked to a collaborator")
    void adminCreatesArtist() throws Exception {
        Collaborator ana = collaborator("Ana", "25.00");

        createUser(adminToken(), """
                {"username":"ana","password":"%s","role":"ARTIST","collaboratorId":%d}"""
                .formatted(PASSWORD, ana.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("ana"))
                .andExpect(jsonPath("$.role").value("ARTIST"))
                .andExpect(jsonPath("$.collaboratorId").value(ana.id()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // And the new account works end to end.
        login("ana", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a duplicate username is a 409, so a race cannot create two accounts with one name")
    void duplicateUsernameIsConflict() throws Exception {
        String admin = adminToken();
        String body = """
                {"username":"twice","password":"%s","role":"ADMIN"}""".formatted(PASSWORD);

        createUser(admin, body).andExpect(status().isCreated());
        createUser(admin, body).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an ARTIST without a collaboratorId is refused")
    void artistMustBeLinkedToACollaborator() throws Exception {
        // Such a user could never be checked for ownership, so it must not exist. Refused in AuthService
        // for a readable message, and refused again by the CHECK constraint in V2__app_user.sql.
        createUser(adminToken(), """
                {"username":"floating","password":"%s","role":"ARTIST"}""".formatted(PASSWORD))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("collaboratorId")));
    }

    @Test
    @DisplayName("an ADMIN with a collaboratorId is refused")
    void adminMustNotBeLinkedToACollaborator() throws Exception {
        Collaborator ana = collaborator("Ana", "25.00");

        createUser(adminToken(), """
                {"username":"confused","password":"%s","role":"ADMIN","collaboratorId":%d}"""
                .formatted(PASSWORD, ana.id()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("linking to a collaborator that does not exist is a 404")
    void unknownCollaboratorIsNotFound() throws Exception {
        createUser(adminToken(), """
                {"username":"ghost","password":"%s","role":"ARTIST","collaboratorId":9999}""".formatted(PASSWORD))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a short password and an unknown role are both 400")
    void invalidUserPayloadsAreBadRequests() throws Exception {
        String admin = adminToken();

        createUser(admin, """
                {"username":"weak","password":"short","role":"ADMIN"}""")
                .andExpect(status().isBadRequest());
        createUser(admin, """
                {"username":"odd","password":"%s","role":"WIZARD"}""".formatted(PASSWORD))
                .andExpect(status().isBadRequest());
        createUser(admin, """
                {"username":"","password":"%s","role":"ADMIN"}""".formatted(PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the database refuses a half-defined user even when the service is bypassed")
    void databaseEnforcesTheRoleLinkRule() {
        // Straight to the repository, skipping AuthService's checks, the way a stray script or a future bug
        // would. The CHECK constraint in V2 is the backstop that still holds.
        assertThatThrownBy(() -> appUsers.create("sneaky", "$2a$10$not-a-real-hash", Role.ARTIST, null))
                .as("an ARTIST with no collaborator must be rejected by the database")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> appUsers.create("sneaky2", "$2a$10$not-a-real-hash", Role.ADMIN, 1L))
                .as("an ADMIN linked to a collaborator must be rejected by the database")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
