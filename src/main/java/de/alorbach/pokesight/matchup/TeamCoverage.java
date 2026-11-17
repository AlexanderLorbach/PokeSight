package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.TypeChart;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which types the team's damaging moves cover, all in type chart order.
 *
 * @param moveTypes   the types of the team's moves
 * @param missing     the types none of its moves has
 * @param unhit       the types none of its moves hits super effectively (as their only type)
 * @param suggestions missing move types that would hit some of {@code unhit} super effectively, most of them first
 */
public record TeamCoverage(List<PokemonType> moveTypes, List<PokemonType> missing, List<PokemonType> unhit,
                           List<Suggestion> suggestions) {

    /** A move type the team lacks and the uncovered types it hits super effectively. */
    public record Suggestion(PokemonType moveType, List<PokemonType> hits) {
        public Suggestion {
            hits = List.copyOf(hits);
        }
    }

    public TeamCoverage {
        moveTypes = List.copyOf(moveTypes);
        missing = List.copyOf(missing);
        unhit = List.copyOf(unhit);
        suggestions = List.copyOf(suggestions);
    }

    /** Moves the ruleset does not know (status moves, moves of other games) are left out. */
    public static TeamCoverage of(Team team, Ruleset ruleset) {
        TypeChart chart = ruleset.typeChart();
        Set<PokemonType> present = team.members().stream()
                .flatMap(member -> member.moves().stream())
                .map(ruleset::findMove)
                .flatMap(Optional::stream)
                .map(Move::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PokemonType.class)));
        List<PokemonType> types = List.copyOf(chart.types());
        List<PokemonType> unhit = types.stream()
                .filter(defender -> present.stream().noneMatch(attacker -> chart.multiplier(attacker, defender) > 1))
                .toList();
        List<PokemonType> missing = types.stream().filter(type -> !present.contains(type)).toList();
        List<Suggestion> suggestions = missing.stream()
                .map(attacker -> new Suggestion(attacker, unhit.stream()
                        .filter(defender -> chart.multiplier(attacker, defender) > 1)
                        .toList()))
                .filter(suggestion -> !suggestion.hits().isEmpty())
                .sorted(Comparator.comparingInt((Suggestion suggestion) -> suggestion.hits().size()).reversed())
                .toList();
        return new TeamCoverage(types.stream().filter(present::contains).toList(), missing, unhit, suggestions);
    }
}
