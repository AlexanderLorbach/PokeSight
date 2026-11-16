package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.Species;

/**
 * Whether a Pokémon hits harder with physical or special moves, by its base Attack and Sp. Atk: a hint for choosing
 * moves. Natures, EVs and items can shift it; within 10 % both kinds work about as well.
 */
public enum AttackStyle {
    PHYSICAL, SPECIAL, MIXED;

    /**
     * Base stats less than 10 % apart count as mixed: the difference is smaller than a nature's. In tenths, so 110 vs
     * 100 is exactly the margin (no floating-point rounding).
     */
    private static final int MIXED_MARGIN_TENTHS = 11;

    public static AttackStyle of(Species.Form form) {
        int attack = form.stat(Species.Stat.ATTACK);
        int specialAttack = form.stat(Species.Stat.SPECIAL_ATTACK);
        if (attack * 10 >= specialAttack * MIXED_MARGIN_TENTHS) {
            return PHYSICAL;
        }
        if (specialAttack * 10 >= attack * MIXED_MARGIN_TENTHS) {
            return SPECIAL;
        }
        return MIXED;
    }
}
