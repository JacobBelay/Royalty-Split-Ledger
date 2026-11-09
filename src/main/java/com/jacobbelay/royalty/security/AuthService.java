package com.jacobbelay.royalty.security;

import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.BusinessRuleException;
import com.jacobbelay.royalty.common.NotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Sign-in and user creation: the only place that touches passwords.
 *
 * <p>Read next: api/AuthController.java (the HTTP layer over this) and {@link TokenService}.
 */
@Service
public class AuthService {

    /**
     * A real BCrypt hash of a value nobody can guess, used only to burn the same CPU time when the username
     * does not exist. Without it, "no such user" returns in microseconds while "wrong password" takes the
     * ~100ms BCrypt deliberately costs — and that difference alone tells an attacker which usernames are
     * real (a "user enumeration" leak). Comparing against this hash makes both paths cost the same.
     */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOa1u8XJDEuWCjMnDPbfmLLhPGqOq7jDO";

    private final AppUserRepository users;
    private final CollaboratorRepository collaborators;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    public AuthService(AppUserRepository users, CollaboratorRepository collaborators,
                       PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.collaborators = collaborators;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    /**
     * Verifies a password and returns a freshly signed token.
     *
     * @throws BadCredentialsException if the username is unknown OR the password is wrong. Deliberately the
     *         same exception and the same message for both, so the 401 response cannot be used to find out
     *         which usernames exist.
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String rawPassword) {
        Optional<AppUser> found = users.findByUsername(username);

        // matches() runs either way; see DUMMY_HASH above for why.
        String hash = found.map(AppUser::passwordHash).orElse(DUMMY_HASH);
        boolean passwordOk = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !passwordOk) {
            throw new BadCredentialsException("invalid username or password");
        }
        AppUser user = found.get();
        return new LoginResult(tokens.issue(user), tokens.tokenTtlSeconds(), user.role(), user.collaboratorId());
    }

    /**
     * Creates an account. Only an ADMIN can reach this (see {@link SecurityConfig}), because otherwise
     * anyone could sign themselves up as ADMIN.
     *
     * <p>The two role rules below are also CHECK constraints in V2__app_user.sql. They are repeated here to
     * produce a readable 422 instead of a raw constraint violation — the same "validate in Java for the
     * message, enforce in SQL for the guarantee" split the ledger schema uses.
     *
     * @throws BusinessRuleException if the role and the collaborator link disagree
     * @throws NotFoundException     if the collaborator does not exist
     */
    @Transactional
    public AppUser createUser(String username, String rawPassword, Role role, Long collaboratorId) {
        if (role == Role.ARTIST && collaboratorId == null) {
            throw new BusinessRuleException("an ARTIST user must be linked to a collaboratorId");
        }
        if (role == Role.ADMIN && collaboratorId != null) {
            throw new BusinessRuleException("an ADMIN user must not be linked to a collaboratorId");
        }
        if (collaboratorId != null && collaborators.findById(collaboratorId).isEmpty()) {
            throw new NotFoundException("collaborator " + collaboratorId + " not found");
        }
        // encode() applies BCrypt with a random per-password salt, so two users with the same password get
        // different hashes and a stolen hash cannot be looked up in a precomputed table.
        return users.create(username, passwordEncoder.encode(rawPassword), role, collaboratorId);
    }

    /** What a successful sign-in produces. Carries no password and no hash. */
    public record LoginResult(String token, long expiresInSeconds, Role role, Long collaboratorId) {
    }
}
