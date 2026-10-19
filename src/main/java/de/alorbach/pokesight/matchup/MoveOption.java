package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;

/**
 * One of the team's moves against a defender.
 *
 * @param effectiveness type multiplier against the defender (0, 0.25 ... 4)
 * @param stab          the move's type is one of the user's types (same-type attack bonus, 1.5x)
 * @param score         power x effectiveness x STAB; stats, level and abilities are not taken into account
 */
public record MoveOption(PokemonId user, int moveId, double effectiveness, boolean stab, double score) {
}
