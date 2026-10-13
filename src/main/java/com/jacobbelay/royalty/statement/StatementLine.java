package com.jacobbelay.royalty.statement;

import com.jacobbelay.royalty.money.Money;

/** One row of a distributor statement: "release X earned this much this period". */
public record StatementLine(long releaseId, Money amount) {
}
