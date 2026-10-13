package com.jacobbelay.royalty.split;

import com.jacobbelay.royalty.money.Money;

/**
 * The output of {@link SplitCalculator}: how many whole cents one collaborator earned from one amount.
 *
 * <p>This is <em>gross earnings</em>, before advances are recouped. The next step,
 * {@link com.jacobbelay.royalty.recoupment.RecoupmentCalculator}, decides how much of it repays an advance
 * and how much becomes payable.
 */
public record Allocation(long collaboratorId, Money amount) {
}
