package com.jacobbelay.royalty.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Makes rejected requests look like every other error in this API.
 *
 * <p>Why this class has to exist: api/GlobalExceptionHandler.java only sees exceptions that reach a
 * controller. Authentication and authorization are decided EARLIER, in the servlet filter chain, so Spring
 * Security writes those responses itself — by default an empty body with an HTML error page shape. A client
 * would then get RFC 7807 problem JSON for a bad amount but something else entirely for a bad token. This
 * class implements both security hooks and writes the same {@code application/problem+json} shape.
 *
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} → <b>401</b>: we do not know who you are (missing, malformed,
 *       expired or badly signed token).</li>
 *   <li>{@link AccessDeniedHandler} → <b>403</b>: we know who you are, and you may not do this.</li>
 * </ul>
 * Getting those two the right way round matters: a 401 tells a client "sign in again", a 403 tells it
 * "signing in again will not help".
 */
@Component
public class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemSecurityHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // The standard header for "this endpoint wants a bearer token"; tells a client HOW to authenticate.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpStatus.UNAUTHORIZED,
                "authentication required: send a bearer token from POST /api/auth/login");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // Deliberately vague: naming the missing role would tell a caller what to go after.
        write(response, HttpStatus.FORBIDDEN, "your account is not allowed to perform this action");
    }

    private void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
