package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;

import java.util.List;
import java.util.Objects;

/**
 * A complete, balanced accounting event: two or more {@link EntryLine}s whose debits equal their credits.
 *
 * <p><b>Why "balanced" is the core invariant.</b> If every entry balances, then across the whole ledger
 * total debits == total credits, so every dollar that entered an account left another one. Money can't
 * appear or disappear through a bug. That's what makes the numbers auditable.
 *
 * <p>This invariant is enforced twice, deliberately:
 * <ul>
 *   <li>here, in the constructor: an unbalanced entry can't even be created, and the error names the gap;</li>
 *   <li>in Postgres, by the deferred {@code journal_line_balanced} trigger (V1__ledger_schema.sql), which
 *       catches anything that bypasses this class.</li>
 * </ul>
 *
 * <p>Instances are built by {@link Postings} and written by {@link LedgerRepository#post}.
 * The list is copied with {@code List.copyOf}, so the entry is immutable once validated.
 */
public record JournalEntry(EntryKind kind, String description, List<EntryLine> lines) {

    public JournalEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two lines");
        }
        Money debits = total(lines, Side.DEBIT);
        Money credits = total(lines, Side.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("unbalanced entry: debits " + debits + " != credits " + credits);
        }
    }

    public Money totalDebits() {
        return total(lines, Side.DEBIT);
    }

    private static Money total(List<EntryLine> lines, Side side) {
        Money sum = Money.ZERO;
        for (EntryLine line : lines) {
            if (line.side() == side) {
                sum = sum.plus(line.amount());
            }
        }
        return sum;
    }
}
