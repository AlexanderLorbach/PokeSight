package de.alorbach.pokesight.ruleset;

import de.alorbach.pokesight.YamlResources;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which damaging moves each Pokémon learns in one game, and how. Generated from PokeAPI by
 * {@code tools/GenerateLearnsets.java} into {@code learnsets/<id>.yaml}; ROM hacks have none.
 *
 * @param machines move id -> the machine that teaches it ("TM26", "HM04")
 * @param pokemon  national dex number -> learnset; species the game does not have are missing
 */
public record Learnsets(String id, Map<Integer, String> machines, Map<Integer, Learnset> pokemon) {

    public Learnsets {
        Objects.requireNonNull(id, "id");
        machines = machines == null ? Map.of() : Map.copyOf(machines);
        pokemon = pokemon == null ? Map.of() : Map.copyOf(pokemon);
    }

    /** Loads {@code learnsets/<id>.yaml} from the classpath. */
    public static Learnsets load(String id) {
        File file = YamlResources.read("learnsets/" + id + ".yaml", File.class);
        return new Learnsets(id, file.machines(), file.pokemon());
    }

    /** A form's learnset (its species' one unless the form learns other moves); empty if the game lacks the species. */
    public Optional<Learnset> find(int speciesId, int form) {
        return Optional.ofNullable(pokemon.get(speciesId)).map(learnset -> learnset.form(form));
    }

    public Optional<String> machine(int moveId) {
        return Optional.ofNullable(machines.get(moveId));
    }

    /** The YAML file's layout. */
    record File(Map<Integer, String> machines, Map<Integer, Learnset> pokemon) {
        File {
            Objects.requireNonNull(pokemon, "pokemon");
        }
    }
}
