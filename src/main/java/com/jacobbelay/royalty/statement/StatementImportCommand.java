package com.jacobbelay.royalty.statement;

import java.util.List;

/**
 * Input to {@link StatementImportService#importStatement}, built by the API layer from the JSON body.
 *
 * @param source      who sent it, e.g. "DistroKid"
 * @param externalRef their identifier for this statement, e.g. "2026-08". Together with {@code source}
 *                    this is the idempotency key (UNIQUE in {@code statement_import}).
 */
public record StatementImportCommand(String source, String externalRef, List<StatementLine> lines) {
}
