package de.alorbach.pokesight.ruleset;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A species in its base form, plus the alternate forms whose types differ (keyed by the game's form index).
 *
 * @param stats     base stats in the order of {@link Stat}; Gen 1's Special counts as both special stats
 * @param abilities identifiers of the abilities it can have in this generation (none before Gen 3)
 */
public record Species(Map<Language, String> name, List<PokemonType> types, List<Integer> stats, List<String> abilities,
                      Map<Integer, Form> forms) {

    /** Base stats in the order they are stored. */
    public enum Stat { HP, ATTACK, DEFENSE, SPECIAL_ATTACK, SPECIAL_DEFENSE, SPEED }

    public Species {
        name = Map.copyOf(Objects.requireNonNull(name, "name"));
        types = requireOneOrTwo(types);
        stats = requireStats(stats);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        forms = forms == null ? Map.of() : Map.copyOf(forms);
    }

    public String name(Language language) {
        return name.get(language);
    }

    /** The given form; forms that are not listed are identical to the base form. */
    public Form form(int formIndex) {
        return forms.getOrDefault(formIndex, new Form(name, types, null, null, stats, abilities));
    }

    /**
     * The form games set in this region show (Alolan Rattata in Sun/Moon): the first form of that region, or the base
     * form (0) if the species has none or the region is null.
     */
    public int regionalForm(String region) {
        return forms.entrySet().stream()
                .filter(entry -> region != null && region.equals(entry.getValue().region()))
                .mapToInt(Map.Entry::getKey)
                .min()
                .orElse(0);
    }

    /**
     * @param sprite sprite file name of an alternate form; null for the base form, whose sprite is its dex number
     * @param region the region of a regional form ("alola", "galar", "hisui", "paldea"); null for other forms
     */
    public record Form(Map<Language, String> name, List<PokemonType> types, String sprite, String region,
                       List<Integer> stats, List<String> abilities) {

        public Form {
            name = Map.copyOf(Objects.requireNonNull(name, "name"));
            types = requireOneOrTwo(types);
            stats = requireStats(stats);
            abilities = abilities == null ? List.of() : List.copyOf(abilities);
        }

        public String name(Language language) {
            return name.get(language);
        }

        public int stat(Stat stat) {
            return stats.get(stat.ordinal());
        }
    }

    private static List<PokemonType> requireOneOrTwo(List<PokemonType> types) {
        List<PokemonType> copy = List.copyOf(Objects.requireNonNull(types, "types"));
        if (copy.isEmpty() || copy.size() > 2) {
            throw new IllegalArgumentException("A Pokémon has one or two types, got " + copy);
        }
        return copy;
    }

    private static List<Integer> requireStats(List<Integer> stats) {
        List<Integer> copy = List.copyOf(Objects.requireNonNull(stats, "stats"));
        if (copy.size() != Stat.values().length || copy.stream().anyMatch(value -> value <= 0)) {
            throw new IllegalArgumentException("Base stats need " + Stat.values().length + " positive values, got " + copy);
        }
        return copy;
    }
}
