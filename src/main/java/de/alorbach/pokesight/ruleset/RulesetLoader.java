package de.alorbach.pokesight.ruleset;

import de.alorbach.pokesight.YamlResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads a ruleset from the YAML files in {@code rulesets/<id>/} on the classpath. */
public final class RulesetLoader {

    private RulesetLoader() {
    }

    public static Ruleset load(String id) {
        String directory = "rulesets/" + id + "/";
        TypeChartFile typeChart = YamlResources.read(directory + "type-chart.yaml", TypeChartFile.class);
        PokemonFile pokemon = YamlResources.read(directory + "pokemon.yaml", PokemonFile.class);
        MovesFile moves = YamlResources.read(directory + "moves.yaml", MovesFile.class);

        TypeChart chart = new TypeChart(typeChart.types(), typeChart.effectiveness());
        pokemon.pokemon().forEach((speciesId, species) -> {
            List<Species.Form> forms = new ArrayList<>(species.forms().values());
            forms.add(species.form(0));
            for (Species.Form form : forms) {
                form.types().stream().filter(type -> !chart.types().contains(type)).findFirst().ifPresent(type -> {
                    throw new IllegalStateException("Species " + speciesId + " in ruleset " + id + " has unknown type " + type);
                });
            }
        });
        moves.moves().forEach((moveId, move) -> {
            if (!chart.types().contains(move.type())) {
                throw new IllegalStateException("Move " + moveId + " in ruleset " + id + " has unknown type " + move.type());
            }
        });
        return new Ruleset(id, chart, pokemon.pokemon(), moves.moves());
    }

    // One record per YAML file layout.
    record TypeChartFile(Map<PokemonType, Map<Language, String>> types,
                         Map<PokemonType, Map<PokemonType, Double>> effectiveness) {
        TypeChartFile {
            Objects.requireNonNull(types, "types");
            Objects.requireNonNull(effectiveness, "effectiveness");
        }
    }

    record PokemonFile(Map<Integer, Species> pokemon) {
        PokemonFile {
            Objects.requireNonNull(pokemon, "pokemon");
        }
    }

    record MovesFile(Map<Integer, Move> moves) {
        MovesFile {
            Objects.requireNonNull(moves, "moves");
        }
    }
}
