package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.advance.AdvanceService;
import com.jacobbelay.royalty.api.ApiDtos.AdvanceRequest;
import com.jacobbelay.royalty.api.ApiDtos.BalancesResponse;
import com.jacobbelay.royalty.api.ApiDtos.CollaboratorResponse;
import com.jacobbelay.royalty.api.ApiDtos.CreateCollaboratorRequest;
import com.jacobbelay.royalty.api.ApiDtos.IdResponse;
import com.jacobbelay.royalty.api.ApiDtos.PayoutResponse;
import com.jacobbelay.royalty.collaborator.Collaborator;
import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.ledger.Account;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.payout.PayoutResult;
import com.jacobbelay.royalty.payout.PayoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Everything addressed to one collaborator: create, read balances, give an advance, request a payout.
 *
 * <p>Controllers stay thin: translate JSON to domain types, call one service, translate back. No money
 * logic lives here. That's all in the services and the pure core, where it's unit-tested.
 *
 * <p>Status codes tell clients what happened without parsing the body: 201 Created for a new payout,
 * 200 OK for an idempotent replay of an old one (see {@link #requestPayout}).
 */
@RestController
@RequestMapping("/api/collaborators")
public class CollaboratorController {

    private static final Money DEFAULT_THRESHOLD = Money.of("50.00");   // mirrors the schema default

    private final CollaboratorRepository collaborators;
    private final LedgerRepository ledger;
    private final AdvanceService advances;
    private final PayoutService payouts;

    public CollaboratorController(CollaboratorRepository collaborators, LedgerRepository ledger,
                                  AdvanceService advances, PayoutService payouts) {
        this.collaborators = collaborators;
        this.ledger = ledger;
        this.advances = advances;
        this.payouts = payouts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollaboratorResponse create(@Valid @RequestBody CreateCollaboratorRequest request) {
        Money threshold = request.payoutThreshold() == null ? DEFAULT_THRESHOLD : new Money(request.payoutThreshold());
        Collaborator c = collaborators.create(request.name(), threshold);
        return new CollaboratorResponse(c.id(), c.name(), c.payoutThreshold().amount());
    }

    /**
     * Derived live from the ledger. There is no stored balance to go stale.
     *
     * <p>{@code @PreAuthorize} is where "an artist reads only their own balances" is enforced.
     * security/SecurityConfig.java has already established that the caller is an ADMIN or an ARTIST; only
     * this check can compare the {@code {id}} in the URL with the collaborator the caller's token is tied
     * to. {@code #id} refers to the method argument and {@code authentication} to the current caller;
     * returning false produces a 403 from security/ProblemSecurityHandler.java.
     *
     * <p>Without it, any signed-in artist could read every other artist's earnings by changing the number
     * in the URL.
     */
    @PreAuthorize("@access.canActForCollaborator(authentication, #id)")
    @GetMapping("/{id}/balances")
    public BalancesResponse balances(@PathVariable long id) {
        Collaborator c = collaborators.findById(id)
                .orElseThrow(() -> new NotFoundException("collaborator " + id + " not found"));
        BigDecimal payable = ledger.balance(Account.payable(id)).amount();
        BigDecimal advance = ledger.balance(Account.advanceReceivable(id)).amount();
        return new BalancesResponse(id, payable, advance, c.payoutThreshold().amount());
    }

    @PostMapping("/{id}/advances")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse issueAdvance(@PathVariable long id, @Valid @RequestBody AdvanceRequest request) {
        return new IdResponse(advances.issue(id, new Money(request.amount())));
    }

    /**
     * The client generates a unique Idempotency-Key (e.g. a UUID) per intended payout and re-sends the SAME
     * key if it retries. That's how a timeout-then-retry avoids paying twice. (Stripe's API uses the same pattern.)
     *
     * <p>Same ownership rule as {@link #balances}: an artist may request their own payout, nobody else's.
     * Note that authorization is only the outer gate — the money rules (threshold, idempotency, row lock)
     * still apply inside payout/PayoutService.java, so a permitted caller cannot be paid twice either.
     */
    @PreAuthorize("@access.canActForCollaborator(authentication, #id)")
    @PostMapping("/{id}/payouts")
    public ResponseEntity<PayoutResponse> requestPayout(@PathVariable long id,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PayoutResult result = payouts.requestPayout(id, idempotencyKey);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(PayoutResponse.from(result));
    }
}
