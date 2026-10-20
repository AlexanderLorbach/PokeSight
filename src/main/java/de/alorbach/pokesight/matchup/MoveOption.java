package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;

/**
 * One of the team's moves against a defender.
 *
 * @param effectiveness type multiplier against the defender (0, 0.25 ... 4), with an ability it surely has
 * @param stab          the move's type is one of the user's types (same-type attack bonus, 1.5x)
 * @param score         power x effectiveness x STAB x the user's attacking base stat / the defender's defending base
 *                      stat (Attack/Defense for physical, Sp. Atk/Sp. Def for special moves); level, natures and
 *                      stat changes are not known
 * @param caveat        name of an ability the defender may have that changes this move's effectiveness; null if none
 */
public record MoveOption(PokemonId user, int moveId, double effectiveness, boolean stab, double score, String caveat) {
}
