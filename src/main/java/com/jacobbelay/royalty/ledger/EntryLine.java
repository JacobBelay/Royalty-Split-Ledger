package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;

import java.util.Objects;

/**
 * One line of a {@link JournalEntry}: "debit (or credit) this account by this amount".
 * Persisted as one {@code journal_line} row by {@link LedgerRepository#post}.
 *
 * <p>Amounts are always positive; direction comes from {@link Side}. A "negative debit" would be an
 * ambiguous way of writing a credit, so it's rejected here and by {@code CHECK (amount > 0)} in the schema.
 */
public record EntryLine(Account account, Side side, Money amount) {

    public EntryLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("line amounts must be positive, got " + amount);
        }
    }

    public static EntryLine debit(Account account, Money amount) {
        return new EntryLine(account, Side.DEBIT, amount);
    }

    public static EntryLine credit(Account account, Money amount) {
        return new EntryLine(account, Side.CREDIT, amount);
    }
}
