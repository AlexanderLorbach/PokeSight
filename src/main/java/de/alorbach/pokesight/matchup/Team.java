package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.catching.Pokedex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    /** One species evolving into another, by national dex number (Ralts 280 into Kirlia 281). */
    public record Evolution(int fromSpecies, int toSpecies) {
    }

    /**
     * This team after a member evolved into {@code evolved} (read in battle): that member becomes {@code evolved}
     * and keeps its moves and slot. Empty, so nothing is guessed, if {@code evolved} is already in the team (in any
     * form), not exactly one member is an earlier stage of it (two Chimchar: which one evolved is unknown), or the
     * player declined this evolution (also its later stages: declined Kirlia blocks Gardevoir).
     *
     * @param declined evolutions the player undid, see {@link #undoneEvolutions}
     */
    public Optional<Team> withEvolution(PokemonId evolved, Pokedex pokedex, Collection<Evolution> declined) {
        if (members.stream().anyMatch(member -> member.pokemon().speciesId() == evolved.speciesId())) {
            return Optional.empty();
        }
        List<Member> earlierStages = members.stream()
                .filter(member -> pokedex.isAncestorOrEqual(member.pokemon().speciesId(), evolved.speciesId()))
                .toList();
        if (earlierStages.size() != 1) {
            return Optional.empty();
        }
        Member before = earlierStages.getFirst();
        boolean isDeclined = declined.stream().anyMatch(evolution -> evolution.fromSpecies() == before.pokemon().speciesId()
                && pokedex.isAncestorOrEqual(evolution.toSpecies(), evolved.speciesId()));
        if (isDeclined) {
            return Optional.empty();
        }
        return Optional.of(new Team(members.stream()
                .map(member -> member == before ? new Member(evolved, member.moves()) : member)
                .toList()));
    }

    /**
     * The evolutions undone from this team to {@code edited}: a species left the team and one of its earlier stages
     * joined it (Kirlia changed back to Ralts: Ralts into Kirlia). The player knows better than the battle reading.
     */
    public List<Evolution> undoneEvolutions(Team edited, Pokedex pokedex) {
        Set<Integer> before = species();
        Set<Integer> after = edited.species();
        List<Evolution> undone = new ArrayList<>();
        for (int removed : before) {
            if (after.contains(removed)) {
                continue;
            }
            after.stream()
                    .filter(added -> !before.contains(added) && added != removed && pokedex.isAncestorOrEqual(added, removed))
                    .forEach(added -> undone.add(new Evolution(added, removed)));
        }
        return undone;
    }

    /** The national dex numbers of the members, in team order. */
    public Set<Integer> species() {
        Set<Integer> species = new LinkedHashSet<>();
        members.forEach(member -> species.add(member.pokemon().speciesId()));
        return species;
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
