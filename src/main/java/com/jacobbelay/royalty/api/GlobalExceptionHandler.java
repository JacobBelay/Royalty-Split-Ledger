package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.common.BusinessRuleException;
import com.jacobbelay.royalty.common.ConflictException;
import com.jacobbelay.royalty.common.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates business exceptions into HTTP responses, in one place.
 *
 * <p>Responses use RFC 7807 "problem details" ({@link ProblemDetail}), a standard JSON error shape:
 * {@code {"type":..., "title":"Conflict", "status":409, "detail":"statement ... already imported ..."}}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} makes Spring's own errors (malformed JSON, failed
 * {@code @Valid} checks, missing Idempotency-Key header) come back in the same shape, as 400s.
 *
 * <pre>
 *  IllegalArgumentException  400  input broke a domain rule (shares != 100%, negative amount...)
 *  NotFoundException         404
 *  ConflictException         409  clashes with something already recorded
 *  DuplicateKeyException     409  a UNIQUE constraint caught a race the service didn't
 *  BusinessRuleException     422  valid request, rule says no (below payout threshold)
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Raw SQL error text can leak table names, so a generic message is returned instead. */
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicateKey(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "this request conflicts with an existing record");
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail unprocessable(BusinessRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
