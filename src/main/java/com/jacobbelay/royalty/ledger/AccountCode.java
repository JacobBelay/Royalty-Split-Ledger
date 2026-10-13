package com.jacobbelay.royalty.ledger;

/**
 * The three kinds of account in this ledger. Must match the CHECK on {@code journal_line.account_code}
 * in V1__ledger_schema.sql.
 *
 * <table>
 *   <tr><th>Account</th><th>Type</th><th>Normal side</th><th>Meaning of a positive balance</th></tr>
 *   <tr><td>CASH</td><td>asset</td><td>debit</td><td>money in our bank</td></tr>
 *   <tr><td>ROYALTY_PAYABLE (per collaborator)</td><td>liability</td><td>credit</td><td>what we owe them</td></tr>
 *   <tr><td>ADVANCE_RECEIVABLE (per collaborator)</td><td>asset</td><td>debit</td><td>advance they haven't earned back yet</td></tr>
 * </table>
 *
 * <p><b>Normal side</b> = the side that <em>increases</em> the balance. Assets grow with debits,
 * liabilities grow with credits. {@link LedgerRepository#balance} uses this to return balances as
 * positive "how much" numbers instead of raw debit-minus-credit.
 */
public enum AccountCode {
    CASH(Side.DEBIT, false),
    ROYALTY_PAYABLE(Side.CREDIT, true),
    ADVANCE_RECEIVABLE(Side.DEBIT, true);

    private final Side normalSide;
    private final boolean perCollaborator;

    AccountCode(Side normalSide, boolean perCollaborator) {
        this.normalSide = normalSide;
        this.perCollaborator = perCollaborator;
    }

    public Side normalSide() {
        return normalSide;
    }

    /** True if each collaborator has their own sub-account, i.e. lines must carry a collaborator id. */
    public boolean perCollaborator() {
        return perCollaborator;
    }
}
