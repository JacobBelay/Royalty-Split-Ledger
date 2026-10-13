package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.payout.PayoutResult;
import com.jacobbelay.royalty.statement.StatementImportResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * The JSON shapes of the API, kept in one file so the whole contract can be read at a glance.
 *
 * <p><b>Why DTOs instead of exposing domain types?</b> The domain ({@link Money}, {@code Share}...) stays free
 * of JSON and validation annotations, and the API can change shape without touching the money math.
 * Money travels as a plain JSON number with at most 2 decimals ({@code @Digits(fraction = 2)}); a request
 * with 12.345 is rejected with 400 before it reaches {@code Money}'s own no-rounding rule.
 *
 * <p>Validation annotations run when a controller parameter is marked {@code @Valid}; failures become a
 * 400 problem response automatically (see {@link GlobalExceptionHandler}).
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ---------- Requests --------------------------------------------------------------------------------

    /** @param payoutThreshold optional; null means the schema default of 50.00 */
    public record CreateCollaboratorRequest(
            @NotBlank String name,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal payoutThreshold) {
    }

    public record ShareRequest(
            @NotNull Long collaboratorId,
            @NotNull @Positive @Digits(integer = 3, fraction = 4) BigDecimal percent) {
    }

    public record CreateReleaseRequest(
            @NotBlank String title,
            @NotEmpty List<@Valid ShareRequest> shares) {
    }

    public record AdvanceRequest(
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record StatementLineRequest(
            @NotNull Long releaseId,
            @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record ImportStatementRequest(
            @NotBlank String source,
            @NotBlank String externalRef,
            @NotEmpty List<@Valid StatementLineRequest> lines) {
    }

    // ---------- Responses -------------------------------------------------------------------------------

    public record CollaboratorResponse(long id, String name, BigDecimal payoutThreshold) {
    }

    public record IdResponse(long id) {
    }

    /** Both balances are on the account's normal side: positive payable = we owe them; positive advance = they owe us. */
    public record BalancesResponse(long collaboratorId, BigDecimal payable, BigDecimal advanceOutstanding, BigDecimal payoutThreshold) {
    }

    public record EarningResponse(long collaboratorId, BigDecimal recouped, BigDecimal payable) {
        static EarningResponse from(Postings.Earning e) {
            return new EarningResponse(e.collaboratorId(), e.recouped().amount(), e.payable().amount());
        }
    }

    public record StatementImportResponse(long statementImportId, boolean duplicate, BigDecimal gross, List<EarningResponse> earnings) {
        static StatementImportResponse from(StatementImportResult r) {
            return new StatementImportResponse(r.statementImportId(), r.duplicate(), r.gross().amount(),
                    r.earnings().stream().map(EarningResponse::from).toList());
        }
    }

    public record PayoutResponse(long payoutId, long collaboratorId, BigDecimal amount, boolean duplicate) {
        static PayoutResponse from(PayoutResult r) {
            return new PayoutResponse(r.payoutId(), r.collaboratorId(), r.amount().amount(), r.duplicate());
        }
    }

    public record TrialBalanceResponse(BigDecimal debits, BigDecimal credits, boolean balanced) {
    }
}
