package de.alorbach.pokesight.ruleset;

import java.util.Map;
import java.util.Optional;

/** Everything needed to judge a matchup in one generation (or ROM hack). Loaded from {@code rulesets/<id>/}. */
public record Ruleset(String id, TypeChart typeChart, Map<Integer, Species> species, Map<Integer, Move> moves) {

    public Ruleset {
        species = Map.copyOf(species);
        moves = Map.copyOf(moves);
    }

    public Optional<Species> findSpecies(int speciesId) {
        return Optional.ofNullable(species.get(speciesId));
    }

    public Optional<Move> findMove(int moveId) {
        return Optional.ofNullable(moves.get(moveId));
    }
}
