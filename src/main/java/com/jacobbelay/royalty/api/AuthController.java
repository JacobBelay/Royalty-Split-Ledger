package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.api.ApiDtos.CreateUserRequest;
import com.jacobbelay.royalty.api.ApiDtos.LoginRequest;
import com.jacobbelay.royalty.api.ApiDtos.LoginResponse;
import com.jacobbelay.royalty.api.ApiDtos.UserResponse;
import com.jacobbelay.royalty.security.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, and account creation for admins.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — open to everyone (it has to be). 200 with a token, or 401.</li>
 *   <li>{@code POST /api/auth/users} — ADMIN only, enforced in security/SecurityConfig.java. If this were
 *       open, anyone could create themselves an ADMIN account and the roles would mean nothing.</li>
 * </ul>
 *
 * <p>This controller holds no logic beyond mapping JSON to and from {@link AuthService}; the password
 * checking lives there so it can be tested without HTTP.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** 200 + token on success. A wrong username and a wrong password both give the same 401. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return LoginResponse.from(auth.login(request.username(), request.password()));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return UserResponse.from(auth.createUser(
                request.username(), request.password(), request.role(), request.collaboratorId()));
    }
}
