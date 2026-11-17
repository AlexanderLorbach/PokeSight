package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the UI shows for one opposing Pokémon, with the name resolved for the chosen language.
 *
 * @param types     its types as they are now: the Tera type alone once it terastallized
 * @param tera      whether {@code types} is a Tera type
 * @param matchup   attack types by multiplier
 * @param bestMoves the team's moves against it, strongest first; empty without a team
 * @param switchIns every team member against it, best switch-in first (see {@link SwitchIn#rating}); empty without a
 *                  team
 */
public record EnemyAnalysis(String name, List<PokemonType> types, boolean tera, Matchup matchup,
                            List<MoveOption> bestMoves, List<SwitchIn> switchIns) {

    /** From this multiplier on, a Pokémon on the field is warned to switch out. */
    public static final double DANGER = 2;
    /** From this multiplier on, the warning says it is likely knocked out in one hit. */
    public static final double ONE_HIT = 4;

    public EnemyAnalysis {
        types = List.copyOf(types);
        bestMoves = List.copyOf(bestMoves);
        switchIns = List.copyOf(switchIns);
    }

    /** How this team member fares against it; empty if it is not in the team (or unknown to the ruleset). */
    public Optional<SwitchIn> switchIn(PokemonId member) {
        return switchIns.stream().filter(switchIn -> switchIn.member().equals(member)).findFirst();
    }

    /**
     * One team member against the opponent: the damage it deals with its best move and the damage it takes from the
     * opponent's attacks of its own types (its STAB moves, the most likely threats). Base stats only: levels, natures
     * and the opponent's real moves are not known, so these rank the team, they are not real damage.
     *
     * @param taken       multiplier per attack type of the opponent, in the order of its types
     * @param worst       the highest of them
     * @param bestMove    its strongest move against the opponent; empty if none of its moves hits
     * @param damageTaken {@code worst} (at least ¼, so immunities still compare) × the opponent's attacking stat / its
     *                    defending stat, the worse of physical and special
     * @param rating      the score of {@code bestMove} / {@code damageTaken}: deals much, takes little
     */
    public record SwitchIn(PokemonId member, Map<PokemonType, Double> taken, double worst, Optional<MoveOption> bestMove,
                           double damageTaken, double rating) {
        public SwitchIn {
            taken = Collections.unmodifiableMap(new LinkedHashMap<>(taken));
            Objects.requireNonNull(bestMove, "bestMove");
        }

        /** The opponent's types it takes {@link #worst} from, if that is at least {@link #DANGER}; empty otherwise. */
        public List<PokemonType> dangerTypes() {
            return worst < DANGER ? List.of()
                    : taken.entrySet().stream().filter(entry -> entry.getValue() == worst).map(Map.Entry::getKey).toList();
        }
    }
}
