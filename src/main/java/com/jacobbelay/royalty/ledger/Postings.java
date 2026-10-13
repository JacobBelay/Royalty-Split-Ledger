package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The only place that decides <em>which accounts move</em> for each business event. Services never build
 * {@link EntryLine}s by hand; they call one of these, so the accounting rules live in one readable file.
 *
 * <p>The three events and their lines (D = debit, C = credit):
 * <pre>
 *  advanceIssued     D ADVANCE_RECEIVABLE(c)  A      they owe us A more
 *                    C CASH                   A      A left our bank
 *
 *  statementReceived D CASH                   G      distributor paid us G
 *                    C ADVANCE_RECEIVABLE(c)  r_c    each collaborator's recouped part reduces what they owe
 *                    C ROYALTY_PAYABLE(c)     p_c    the rest becomes owed to them
 *                    (sum of all r_c + p_c == G, guaranteed by SplitCalculator + RecoupmentCalculator)
 *
 *  payoutSent        D ROYALTY_PAYABLE(c)     P      we owe them P less
 *                    C CASH                   P      P left our bank
 * </pre>
 * Every entry balances by construction; {@link JournalEntry}'s constructor double-checks.
 */
public final class Postings {

    private Postings() {
    }

    public static JournalEntry advanceIssued(long collaboratorId, Money amount) {
        return new JournalEntry(EntryKind.ADVANCE, "Advance to collaborator " + collaboratorId, List.of(
                EntryLine.debit(Account.advanceReceivable(collaboratorId), amount),
                EntryLine.credit(Account.cash(), amount)));
    }

    /**
     * @param gross    total cash received for the statement; must equal the sum of all earnings
     * @param earnings one per collaborator who earned something (zero parts are skipped, since lines must be > 0)
     */
    public static JournalEntry statementReceived(String description, Money gross, List<Earning> earnings) {
        Objects.requireNonNull(earnings, "earnings");
        List<EntryLine> lines = new ArrayList<>();
        lines.add(EntryLine.debit(Account.cash(), gross));
        for (Earning e : earnings) {
            if (e.recouped().isPositive()) {
                lines.add(EntryLine.credit(Account.advanceReceivable(e.collaboratorId()), e.recouped()));
            }
            if (e.payable().isPositive()) {
                lines.add(EntryLine.credit(Account.payable(e.collaboratorId()), e.payable()));
            }
        }
        return new JournalEntry(EntryKind.STATEMENT, description, lines);
    }

    public static JournalEntry payoutSent(long collaboratorId, Money amount) {
        return new JournalEntry(EntryKind.PAYOUT, "Payout to collaborator " + collaboratorId, List.of(
                EntryLine.debit(Account.payable(collaboratorId), amount),
                EntryLine.credit(Account.cash(), amount)));
    }

    /**
     * One collaborator's share of a statement after recoupment
     * (the output of {@link com.jacobbelay.royalty.recoupment.RecoupmentCalculator}).
     */
    public record Earning(long collaboratorId, Money recouped, Money payable) {
    }
}
