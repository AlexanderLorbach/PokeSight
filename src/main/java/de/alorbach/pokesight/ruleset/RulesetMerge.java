package de.alorbach.pokesight.ruleset;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a ruleset from a game's own one and a newer generation's, for games that can take parts of the newer games
 * (Platinum Together's MODERN POKéMON: Fairy type, newer Pokémon). Species keep their own names, base stats and
 * sprites; only the part that is taken over changes.
 */
public final class RulesetMerge {

    private RulesetMerge() {
    }

    /**
     * The newer games' types: the source's type chart, the source's types for every species and form of the base (Ralts
     * becomes Psychic/Fairy) and the source's data of the base's moves (Knock Off 65). Species the source lacks keep
     * their types. Moves keep their names like species do: the game still shows its own ("Faint Attack" in Platinum,
     * "Feint Attack" since Gen 6).
     */
    public static Ruleset withTypesFrom(Ruleset base, Ruleset source) {
        Map<Integer, Species> species = new HashMap<>();
        base.species().forEach((id, own) -> species.put(id, source.findSpecies(id).map(newer -> new Species(own.name(),
                newer.types(), own.stats(), retypedForms(own, newer), own.sprite())).orElse(own)));
        Map<Integer, Move> moves = new HashMap<>();
        base.moves().forEach((id, own) -> moves.put(id, source.findMove(id)
                .map(newer -> new Move(own.name(), newer.type(), newer.power(), newer.category())).orElse(own)));
        return checked(new Ruleset(base.id() + "+types:" + source.id(), source.typeChart(), species, moves));
    }

    /**
     * The source's species and moves that the base lacks (Pokémon of newer games), as the source has them. Types they
     * bring along (Fairy) are added to the chart with the source's effectiveness; pairs of the base's own types keep
     * the base's.
     */
    public static Ruleset withSpeciesFrom(Ruleset base, Ruleset source) {
        Map<Integer, Species> species = new HashMap<>(base.species());
        source.species().forEach(species::putIfAbsent);
        Map<Integer, Move> moves = new HashMap<>(base.moves());
        source.moves().forEach(moves::putIfAbsent);
        return checked(new Ruleset(base.id() + "+species:" + source.id(),
                chartWithMissingTypes(base.typeChart(), source.typeChart()), species, moves));
    }

    private static Map<Integer, Species.Form> retypedForms(Species own, Species newer) {
        Map<Integer, Species.Form> forms = new HashMap<>();
        own.forms().forEach((index, form) -> {
            List<PokemonType> types = newer.forms().containsKey(index) ? newer.form(index).types() : form.types();
            forms.put(index, new Species.Form(form.name(), types, form.sprite(), form.region(), form.stats()));
        });
        return forms;
    }

    private static TypeChart chartWithMissingTypes(TypeChart base, TypeChart source) {
        Set<PokemonType> missing = new LinkedHashSet<>(source.types());
        missing.removeAll(base.types());
        if (missing.isEmpty()) {
            return base;
        }
        Set<PokemonType> all = new LinkedHashSet<>(base.types());
        all.addAll(missing);
        Map<PokemonType, Map<Language, String>> names = new EnumMap<>(PokemonType.class);
        for (PokemonType type : all) {
            TypeChart from = base.types().contains(type) ? base : source;
            Map<Language, String> typeNames = new EnumMap<>(Language.class);
            for (Language language : Language.values()) {
                typeNames.put(language, from.name(type, language));
            }
            names.put(type, typeNames);
        }
        Map<PokemonType, Map<PokemonType, Double>> effectiveness = new EnumMap<>(PokemonType.class);
        for (PokemonType attacker : all) {
            for (PokemonType defender : all) {
                boolean newPair = missing.contains(attacker) || missing.contains(defender);
                double multiplier = (newPair ? source : base).multiplier(attacker, defender);
                if (multiplier != 1.0) {
                    effectiveness.computeIfAbsent(attacker, key -> new EnumMap<>(PokemonType.class)).put(defender, multiplier);
                }
            }
        }
        return new TypeChart(names, effectiveness);
    }

    /** Every type a species, form or move uses must be in the chart, as {@link RulesetLoader} checks for files. */
    private static Ruleset checked(Ruleset ruleset) {
        Set<PokemonType> types = ruleset.typeChart().types();
        ruleset.species().forEach((id, species) -> {
            for (Species.Form form : species.forms().values()) {
                requireKnown(types, form.types(), "Form of species " + id);
            }
            requireKnown(types, species.types(), "Species " + id);
        });
        ruleset.moves().forEach((id, move) -> requireKnown(types, List.of(move.type()), "Move " + id));
        return ruleset;
    }

    private static void requireKnown(Set<PokemonType> chart, List<PokemonType> used, String what) {
        used.stream().filter(type -> !chart.contains(type)).findFirst().ifPresent(type -> {
            throw new IllegalStateException(what + " has type " + type + ", which the merged type chart lacks");
        });
    }
}
