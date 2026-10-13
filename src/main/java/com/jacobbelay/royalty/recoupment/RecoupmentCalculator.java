package com.jacobbelay.royalty.recoupment;

import com.jacobbelay.royalty.money.Money;

import java.util.Objects;

/**
 * Decides how much of a collaborator's new earnings repays their outstanding advance.
 *
 * <p><b>Advance recoupment, in music terms.</b> A label pays an artist $1,000 up front. That isn't a gift;
 * it's prepaid royalties. Until the artist's earnings have covered the $1,000, their royalties go toward
 * paying it back ("recouping") instead of into their pocket. Once recouped, earnings are payable again.
 *
 * <p>Example: outstanding advance $300, new earnings $500 gives $300 recouped and $200 payable.
 * Next statement, outstanding is $0, so everything is payable.
 *
 * <p>Pure function; no state is kept here. The outstanding amount comes from the ledger
 * ({@code ADVANCE_RECEIVABLE} balance, see {@link com.jacobbelay.royalty.ledger.LedgerRepository#balance}),
 * and the result becomes ledger lines via
 * {@link com.jacobbelay.royalty.ledger.Postings#statementReceived}. Called by
 * {@link com.jacobbelay.royalty.statement.StatementImportService} once per collaborator per statement,
 * while that collaborator's row is locked, so two imports can't both recoup the same advance.
 */
public final class RecoupmentCalculator {

    private RecoupmentCalculator() {
    }

    /**
     * @param earned            this statement's gross earnings for one collaborator (from the split); >= 0
     * @param outstandingAdvance how much advance they still owe; values <= 0 mean nothing to recoup
     */
    public static Recoupment apply(Money earned, Money outstandingAdvance) {
        Objects.requireNonNull(earned, "earned");
        Objects.requireNonNull(outstandingAdvance, "outstandingAdvance");
        if (earned.isNegative()) {
            throw new IllegalArgumentException("earnings cannot be negative: " + earned);
        }
        Money owed = Money.max(outstandingAdvance, Money.ZERO);
        Money recouped = Money.min(earned, owed);          // can't recoup more than was earned, or more than is owed
        return new Recoupment(recouped, earned.minus(recouped));
    }

    /**
     * @param recouped goes to the ADVANCE_RECEIVABLE account (reduces what they owe us)
     * @param payable  goes to the ROYALTY_PAYABLE account (what we now owe them)
     *                 Invariant: recouped + payable == earned.
     */
    public record Recoupment(Money recouped, Money payable) {
    }
}
