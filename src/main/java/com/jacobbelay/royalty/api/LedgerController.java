package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.api.ApiDtos.TrialBalanceResponse;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/ledger/trial-balance: total debits vs total credits across the whole ledger.
 * A classic accounting health check; {@code balanced} must always be true (see JournalEntry for why).
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerRepository ledger;

    public LedgerController(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/trial-balance")
    public TrialBalanceResponse trialBalance() {
        LedgerRepository.TrialBalance tb = ledger.trialBalance();
        return new TrialBalanceResponse(tb.debits().amount(), tb.credits().amount(), tb.balanced());
    }
}
