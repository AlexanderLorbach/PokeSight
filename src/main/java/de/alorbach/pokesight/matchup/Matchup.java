package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Attack types grouped by the damage multiplier they deal to a defender, highest first.
 * Neutral (1x) types are left out.
 */
public record Matchup(NavigableMap<Double, List<PokemonType>> byMultiplier) {

    public Matchup {
        TreeMap<Double, List<PokemonType>> copy = new TreeMap<>(Collections.reverseOrder());
        byMultiplier.forEach((multiplier, types) -> copy.put(multiplier, List.copyOf(types)));
        byMultiplier = Collections.unmodifiableNavigableMap(copy);
    }

    public List<PokemonType> typesWith(double multiplier) {
        return byMultiplier.getOrDefault(multiplier, List.of());
    }
}
