package de.alorbach.pokesight.ruleset;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generation-specific type effectiveness. Pairs that are not listed deal neutral (1x) damage. */
public final class TypeChart {

    private final Map<PokemonType, Map<Language, String>> names;
    private final Map<PokemonType, Map<PokemonType, Double>> effectiveness;

    public TypeChart(Map<PokemonType, Map<Language, String>> names,
                     Map<PokemonType, Map<PokemonType, Double>> effectiveness) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("A type chart needs at least one type");
        }
        this.names = Collections.unmodifiableMap(new EnumMap<>(names));
        this.effectiveness = new EnumMap<>(PokemonType.class);
        if (effectiveness != null) {
            effectiveness.forEach((attacker, row) -> {
                requireKnown(attacker);
                row.keySet().forEach(this::requireKnown);
                this.effectiveness.put(attacker, Map.copyOf(row));
            });
        }
    }

    /** The types that exist in this generation, in canonical order. */
    public Set<PokemonType> types() {
        return names.keySet();
    }

    public double multiplier(PokemonType attackType, PokemonType defendingType) {
        return effectiveness.getOrDefault(attackType, Map.of()).getOrDefault(defendingType, 1.0);
    }

    /** Combined multiplier against a single- or dual-typed defender. */
    public double multiplier(PokemonType attackType, List<PokemonType> defendingTypes) {
        double result = 1;
        for (PokemonType defendingType : defendingTypes) {
            result *= multiplier(attackType, defendingType);
        }
        return result;
    }

    public String name(PokemonType type, Language language) {
        return names.getOrDefault(type, Map.of()).getOrDefault(language, type.name());
    }

    private void requireKnown(PokemonType type) {
        if (!names.containsKey(type)) {
            throw new IllegalArgumentException("Type " + type + " is used in the chart but not declared in 'types'");
        }
    }
}
