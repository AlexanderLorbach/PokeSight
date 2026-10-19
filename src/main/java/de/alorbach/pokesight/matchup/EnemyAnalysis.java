package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.List;

/**
 * Everything the UI shows for one opposing Pokémon, with the name resolved for the chosen language.
 *
 * @param bestMoves the team's moves against it, strongest first; empty without a team
 */
public record EnemyAnalysis(String name, List<PokemonType> types, Matchup matchup, List<MoveOption> bestMoves) {

    public EnemyAnalysis {
        types = List.copyOf(types);
        bestMoves = List.copyOf(bestMoves);
    }
}
