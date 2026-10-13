package com.jacobbelay.royalty.ledger;

/**
 * Which business record caused a journal entry: exactly one of the three ids is set.
 * Stored in {@code journal_entry.statement_import_id / advance_id / payout_id} so any ledger line can be
 * traced back to the statement, advance or payout that produced it (an audit trail).
 */
public record EntrySource(Long statementImportId, Long advanceId, Long payoutId) {

    public static EntrySource statement(long statementImportId) {
        return new EntrySource(statementImportId, null, null);
    }

    public static EntrySource advance(long advanceId) {
        return new EntrySource(null, advanceId, null);
    }

    public static EntrySource payout(long payoutId) {
        return new EntrySource(null, null, payoutId);
    }
}
