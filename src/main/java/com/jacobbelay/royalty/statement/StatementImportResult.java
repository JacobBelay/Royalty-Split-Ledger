package com.jacobbelay.royalty.statement;

import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;

import java.util.List;

/**
 * What an import did. Identical for the first import and any duplicate re-send, except {@code duplicate},
 * so a client that retries after a timeout sees exactly the numbers it missed.
 *
 * @param duplicate true if this statement was already imported and nothing new was posted
 * @param earnings  per collaborator: how much was recouped against advances and how much became payable
 */
public record StatementImportResult(long statementImportId, boolean duplicate, Money gross, List<Postings.Earning> earnings) {
}
