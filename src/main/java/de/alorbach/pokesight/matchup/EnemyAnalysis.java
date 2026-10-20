package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the UI shows for one opposing Pokémon, with the name resolved for the chosen language.
 *
 * @param types     its types as they are now: the Tera type alone once it terastallized
 * @param tera      whether {@code types} is a Tera type
 * @param matchup   attack types by multiplier, with an ability it surely has
 * @param abilities abilities that change its type matchups
 * @param bestMoves the team's moves against it, strongest first; empty without a team
 * @param switchIns the team against its attacks, safest first; empty without a team
 */
public record EnemyAnalysis(String name, List<PokemonType> types, boolean tera, Matchup matchup, List<AbilityNote> abilities,
                            List<MoveOption> bestMoves, List<SwitchIn> switchIns) {

    public EnemyAnalysis {
        types = List.copyOf(types);
        abilities = List.copyOf(abilities);
        bestMoves = List.copyOf(bestMoves);
        switchIns = List.copyOf(switchIns);
    }

    /**
     * An ability of the opponent that changes type matchups.
     *
     * @param certain it is the species' only ability, so the opponent has it (and it is applied); otherwise it may
     */
    public record AbilityNote(String name, boolean certain, AbilityEffect effect) {
    }

    /**
     * How one team member takes the opponent's attacks of its own types (its STAB moves, the most likely threats).
     *
     * @param taken multiplier per attack type of the opponent, in the order of its types
     * @param worst the highest of them
     */
    public record SwitchIn(PokemonId member, Map<PokemonType, Double> taken, double worst) {
        public SwitchIn {
            taken = Collections.unmodifiableMap(new LinkedHashMap<>(taken));
        }
    }
}
