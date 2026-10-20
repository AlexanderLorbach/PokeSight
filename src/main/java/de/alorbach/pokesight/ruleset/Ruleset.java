package de.alorbach.pokesight.ruleset;

import java.util.Map;
import java.util.Optional;

/**
 * Everything needed to judge a matchup in one generation (or ROM hack). Loaded from {@code rulesets/<id>/}.
 *
 * @param generation   decides how abilities work (Lightning Rod grants an immunity since Gen 5)
 * @param abilityNames names of the abilities the species can have, by identifier
 */
public record Ruleset(String id, int generation, TypeChart typeChart, Map<Integer, Species> species, Map<Integer, Move> moves,
                      Map<String, Map<Language, String>> abilityNames) {

    public Ruleset {
        species = Map.copyOf(species);
        moves = Map.copyOf(moves);
        abilityNames = Map.copyOf(abilityNames);
    }

    /** The ability's name in the language, or its identifier if the ruleset has no name for it. */
    public String abilityName(String ability, Language language) {
        return abilityNames.getOrDefault(ability, Map.of()).getOrDefault(language, ability);
    }

    public Optional<Species> findSpecies(int speciesId) {
        return Optional.ofNullable(species.get(speciesId));
    }

    public Optional<Move> findMove(int moveId) {
        return Optional.ofNullable(moves.get(moveId));
    }
}
