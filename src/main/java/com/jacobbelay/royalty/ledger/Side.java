package com.jacobbelay.royalty.ledger;

/**
 * Which column of the ledger a line goes in. {@code code} is what's stored in {@code journal_line.side}.
 *
 * <p>"Debit" and "credit" don't mean "bad" and "good". They're just left and right. What they <em>do</em>
 * to a balance depends on the account's normal side (see {@link AccountCode#normalSide()}):
 * a debit increases CASH but decreases ROYALTY_PAYABLE.
 */
public enum Side {
    DEBIT("D"),
    CREDIT("C");

    private final String code;

    Side(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Side fromCode(String code) {
        return switch (code) {
            case "D" -> DEBIT;
            case "C" -> CREDIT;
            default -> throw new IllegalArgumentException("unknown side " + code);
        };
    }
}
