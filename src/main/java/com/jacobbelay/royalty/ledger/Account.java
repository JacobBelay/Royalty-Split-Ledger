package com.jacobbelay.royalty.ledger;

import java.util.Objects;

/**
 * A specific account: an {@link AccountCode} plus, for per-collaborator codes, whose account it is.
 * e.g. {@code Account.payable(7)} is "what we owe collaborator 7".
 *
 * <p>The constructor enforces the same pairing rule as the schema's
 * {@code CHECK ((account_code = 'CASH') = (collaborator_id IS NULL))}, so an invalid account fails in Java
 * with a clear message before it ever reaches SQL.
 *
 * @param collaboratorId null for CASH, required otherwise
 */
public record Account(AccountCode code, Long collaboratorId) {

    public Account {
        Objects.requireNonNull(code, "code");
        if (code.perCollaborator() != (collaboratorId != null)) {
            throw new IllegalArgumentException(code + (code.perCollaborator()
                    ? " needs a collaborator id"
                    : " must not have a collaborator id"));
        }
    }

    public static Account cash() {
        return new Account(AccountCode.CASH, null);
    }

    public static Account payable(long collaboratorId) {
        return new Account(AccountCode.ROYALTY_PAYABLE, collaboratorId);
    }

    public static Account advanceReceivable(long collaboratorId) {
        return new Account(AccountCode.ADVANCE_RECEIVABLE, collaboratorId);
    }
}
