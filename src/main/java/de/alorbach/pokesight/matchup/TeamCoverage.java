package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.TypeChart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which types the team's damaging moves cover, all in type chart order.
 *
 * @param moveTypes      the types of the team's moves
 * @param missing        the types none of its moves has
 * @param unhit          the types none of its moves hits super effectively (as their only type)
 * @param suggestions    missing move types that would hit some of {@code unhit} super effectively: those a team member
 *                       can learn first, then most hits first
 * @param learnsetsKnown whether the game's learnsets were used: then suggestions only name team members that learn a
 *                       move of the type
 */
public record TeamCoverage(List<PokemonType> moveTypes, List<PokemonType> missing, List<PokemonType> unhit,
                           List<Suggestion> suggestions, boolean learnsetsKnown) {

    /**
     * A move type the team lacks, the uncovered types it hits super effectively and where such a move could go.
     *
     * @param emptySlots   team members with fewer than {@value Team#MAX_MOVES} moves: room for it at no cost
     * @param replacements the team's moves that could make way for it: those whose loss costs no type the team hits
     *                     super effectively now (with the new move); if there are none, those that cost the fewest.
     *                     Moves without STAB and with less power first
     */
    public record Suggestion(PokemonType moveType, List<PokemonType> hits, List<Slot> emptySlots,
                             List<Replacement> replacements) {
        public Suggestion {
            hits = List.copyOf(hits);
            emptySlots = List.copyOf(emptySlots);
            replacements = List.copyOf(replacements);
        }

        /** Whether some team member could take such a move. */
        public boolean placeable() {
            return !emptySlots.isEmpty() || !replacements.isEmpty();
        }
    }

    /**
     * A team member with a free move slot.
     *
     * @param learn the move of the suggested type it would learn (see {@link MovePool#best}); empty without learnsets
     */
    public record Slot(PokemonId member, Optional<MovePool.Learnable> learn) {
    }

    /**
     * A team member's move that could be replaced.
     *
     * @param lost  the types the team would no longer hit super effectively; empty for a free swap
     * @param learn the move of the suggested type the member would learn instead; empty without learnsets
     */
    public record Replacement(PokemonId member, int moveId, List<PokemonType> lost, Optional<MovePool.Learnable> learn) {
        public Replacement {
            lost = List.copyOf(lost);
        }
    }

    public TeamCoverage {
        moveTypes = List.copyOf(moveTypes);
        missing = List.copyOf(missing);
        unhit = List.copyOf(unhit);
        suggestions = List.copyOf(suggestions);
    }

    /** One known damaging move of a team member. */
    private record TeamMove(PokemonId member, int moveId, Move move, boolean stab) {
    }

    /** Without learnsets (ROM hacks): every member counts as able to learn every suggested move. */
    public static TeamCoverage of(Team team, Ruleset ruleset) {
        return of(team, ruleset, Optional.empty());
    }

    /**
     * Moves the ruleset does not know (status moves, moves of other games) are left out. With the game's learnsets,
     * a suggested move only goes to members that learn a move of its type; members the learnsets lack (Pokémon
     * newer than the game) count as able to.
     */
    public static TeamCoverage of(Team team, Ruleset ruleset, Optional<MovePool> pool) {
        TypeChart chart = ruleset.typeChart();
        List<TeamMove> moves = team.members().stream()
                .flatMap(member -> member.moves().stream().flatMap(moveId -> ruleset.findMove(moveId).stream()
                        .map(move -> new TeamMove(member.pokemon(), moveId, move, isStab(member.pokemon(), move, ruleset)))))
                .toList();
        Set<PokemonType> present = moves.stream().map(teamMove -> teamMove.move().type())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PokemonType.class)));
        List<PokemonType> types = List.copyOf(chart.types());
        List<PokemonType> unhit = types.stream().filter(defender -> !hitBySome(present, defender, chart)).toList();
        List<PokemonType> covered = types.stream().filter(defender -> !unhit.contains(defender)).toList();
        List<PokemonType> missing = types.stream().filter(type -> !present.contains(type)).toList();
        List<PokemonId> withFreeSlot = team.members().stream()
                .filter(member -> member.moves().size() < Team.MAX_MOVES)
                .map(Team.Member::pokemon)
                .toList();
        List<Suggestion> suggestions = missing.stream()
                .map(attacker -> suggestion(attacker, unhit, covered, moves, withFreeSlot, chart, pool))
                .filter(suggestion -> !suggestion.hits().isEmpty())
                .sorted(Comparator.comparing(Suggestion::placeable).reversed()
                        .thenComparing(Comparator.comparingInt((Suggestion suggestion) -> suggestion.hits().size()).reversed()))
                .toList();
        return new TeamCoverage(types.stream().filter(present::contains).toList(), missing, unhit, suggestions, pool.isPresent());
    }

    private static Suggestion suggestion(PokemonType added, List<PokemonType> unhit, List<PokemonType> covered,
                                         List<TeamMove> moves, List<PokemonId> withFreeSlot, TypeChart chart,
                                         Optional<MovePool> pool) {
        List<Slot> emptySlots = withFreeSlot.stream()
                .filter(member -> canLearn(pool, member, added))
                .map(member -> new Slot(member, best(pool, member, added)))
                .toList();
        List<TeamMove> replaceable = moves.stream().filter(move -> canLearn(pool, move.member(), added)).toList();
        return new Suggestion(added, unhit.stream().filter(defender -> chart.multiplier(added, defender) > 1).toList(),
                emptySlots, replacements(added, moves, replaceable, covered, chart, pool));
    }

    /**
     * The moves that could make way for a move of {@code added}: each of {@code replaceable} is taken out in turn, the
     * new type put in, and the types hit super effectively before but no longer are counted.
     */
    private static List<Replacement> replacements(PokemonType added, List<TeamMove> moves, List<TeamMove> replaceable,
                                                  List<PokemonType> covered, TypeChart chart, Optional<MovePool> pool) {
        record Candidate(TeamMove move, List<PokemonType> lost) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (TeamMove removed : replaceable) {
            Set<PokemonType> remaining = EnumSet.of(added);
            moves.stream().filter(move -> move != removed).forEach(move -> remaining.add(move.move().type()));
            candidates.add(new Candidate(removed, covered.stream().filter(defender -> !hitBySome(remaining, defender, chart)).toList()));
        }
        int fewestLost = candidates.stream().mapToInt(candidate -> candidate.lost().size()).min().orElse(0);
        return candidates.stream()
                .filter(candidate -> candidate.lost().size() == fewestLost)
                .sorted(Comparator.comparing((Candidate candidate) -> candidate.move().stab())
                        .thenComparingInt(candidate -> candidate.move().move().power()))
                .map(candidate -> new Replacement(candidate.move().member(), candidate.move().moveId(), candidate.lost(),
                        best(pool, candidate.move().member(), added)))
                .toList();
    }

    private static boolean canLearn(Optional<MovePool> pool, PokemonId member, PokemonType type) {
        return pool.map(known -> !known.knows(member) || known.best(member, type).isPresent()).orElse(true);
    }

    private static Optional<MovePool.Learnable> best(Optional<MovePool> pool, PokemonId member, PokemonType type) {
        return pool.flatMap(known -> known.best(member, type));
    }

    private static boolean hitBySome(Collection<PokemonType> attackers, PokemonType defender, TypeChart chart) {
        return attackers.stream().anyMatch(attacker -> chart.multiplier(attacker, defender) > 1);
    }

    private static boolean isStab(PokemonId member, Move move, Ruleset ruleset) {
        return ruleset.findSpecies(member.speciesId())
                .map(species -> species.form(member.form()).types().contains(move.type()))
                .orElse(false);
    }
}
