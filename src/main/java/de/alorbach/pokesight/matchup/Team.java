package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;

import java.util.List;
import java.util.Objects;

/** The player's team: up to six Pokémon with up to four moves each (move ids of the ruleset). */
public record Team(List<Member> members) {

    public static final int MAX_MEMBERS = 6;
    public static final int MAX_MOVES = 4;
    public static final Team EMPTY = new Team(List.of());

    public Team {
        members = List.copyOf(members);
        if (members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("A team has at most " + MAX_MEMBERS + " Pokémon, got " + members.size());
        }
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public record Member(PokemonId pokemon, List<Integer> moves) {
        public Member {
            Objects.requireNonNull(pokemon, "pokemon");
            moves = List.copyOf(moves);
            if (moves.size() > MAX_MOVES) {
                throw new IllegalArgumentException("A Pokémon knows at most " + MAX_MOVES + " moves, got " + moves.size());
            }
        }
    }
}
