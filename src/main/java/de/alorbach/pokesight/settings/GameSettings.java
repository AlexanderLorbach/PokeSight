package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.matchup.Team;

import java.util.List;

/**
 * Settings of one game, stored in {@code games/<game id>.yaml}.
 *
 * @param singleArea screen area with the opponent's name box in single battles; null if not selected
 * @param doubleArea screen area with both name boxes in double battles; null if not selected
 * @param team       the player's team, by national dex number, form index and move ids
 * @param movesArea  screen area with the move selection, read by the team editor; null if not selected
 * @param ownArea    screen area with the player's own name box in single battles, to know the active Pokémon; null if
 *                   not selected
 * @param nameArea   screen area with the Pokémon's name next to its moves, read by the team editor; null if not selected
 * @param ownDoubleArea screen area with both of the player's name boxes in double battles; null if not selected
 * @param declinedEvolutions evolutions the player undid in the team editor after PokeSight applied them: not applied
 *                   again from battle readings
 */
public record GameSettings(CaptureRegion singleArea, CaptureRegion doubleArea, List<TeamMember> team, CaptureRegion movesArea,
                           CaptureRegion ownArea, CaptureRegion nameArea, CaptureRegion ownDoubleArea,
                           List<Evolution> declinedEvolutions) {

    public static final GameSettings DEFAULT = new GameSettings(null, null, List.of(), null, null, null, null, List.of());

    public GameSettings {
        team = team == null ? List.of() : List.copyOf(team);
        declinedEvolutions = declinedEvolutions == null ? List.of() : List.copyOf(declinedEvolutions);
    }

    public GameSettings withSingleArea(CaptureRegion area) {
        return new GameSettings(area, doubleArea, team, movesArea, ownArea, nameArea, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withDoubleArea(CaptureRegion area) {
        return new GameSettings(singleArea, area, team, movesArea, ownArea, nameArea, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withTeam(Team newTeam) {
        return new GameSettings(singleArea, doubleArea, newTeam.members().stream()
                .map(member -> new TeamMember(member.pokemon().speciesId(), member.pokemon().form(), member.moves()))
                .toList(), movesArea, ownArea, nameArea, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withMovesArea(CaptureRegion area) {
        return new GameSettings(singleArea, doubleArea, team, area, ownArea, nameArea, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withOwnArea(CaptureRegion area) {
        return new GameSettings(singleArea, doubleArea, team, movesArea, area, nameArea, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withNameArea(CaptureRegion area) {
        return new GameSettings(singleArea, doubleArea, team, movesArea, ownArea, area, ownDoubleArea, declinedEvolutions);
    }

    public GameSettings withOwnDoubleArea(CaptureRegion area) {
        return new GameSettings(singleArea, doubleArea, team, movesArea, ownArea, nameArea, area, declinedEvolutions);
    }

    public GameSettings withDeclinedEvolutions(List<Team.Evolution> evolutions) {
        return new GameSettings(singleArea, doubleArea, team, movesArea, ownArea, nameArea, ownDoubleArea,
                evolutions.stream().map(evolution -> new Evolution(evolution.fromSpecies(), evolution.toSpecies())).toList());
    }

    public Team toTeam() {
        return new Team(team.stream()
                .map(member -> new Team.Member(new PokemonId(member.pokemon(), member.form()), member.moves()))
                .toList());
    }

    public List<Team.Evolution> toDeclinedEvolutions() {
        return declinedEvolutions.stream().map(evolution -> new Team.Evolution(evolution.from(), evolution.to())).toList();
    }

    /** One team slot: {@code { pokemon: 390, form: 0, moves: [52, 10] }}. */
    public record TeamMember(int pokemon, int form, List<Integer> moves) {
        public TeamMember {
            moves = moves == null ? List.of() : List.copyOf(moves);
        }
    }

    /** A species evolving into another, by national dex number: {@code { from: 280, to: 281 }}. */
    public record Evolution(int from, int to) {
    }
}
