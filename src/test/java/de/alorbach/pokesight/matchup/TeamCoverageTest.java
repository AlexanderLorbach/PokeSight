package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.alorbach.pokesight.ruleset.PokemonType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Type coverage of a team's moves on the Gen 4 type chart (17 types, no Fairy). */
class TeamCoverageTest {

    private static final Ruleset GEN4 = RulesetLoader.load("gen4");

    private static final int FLAMETHROWER = 53;
    private static final int SURF = 57;
    private static final int PSYBEAM = 60;
    private static final int PSYCHIC_MOVE = 94;
    private static final int GROWL = 45; // status: not in the ruleset

    @Test
    void listsMoveTypesMissingTypesAndWhatNothingHitsSuperEffectively() {
        TeamCoverage coverage = TeamCoverage.of(team(List.of(FLAMETHROWER, SURF)), GEN4);

        assertThat(coverage.moveTypes()).containsExactly(FIRE, WATER);
        assertThat(coverage.missing()).hasSize(15).doesNotContain(FIRE, WATER);
        // Fire hits Grass, Ice, Bug, Steel; Water hits Fire, Ground, Rock.
        assertThat(coverage.unhit()).doesNotContain(GRASS, ICE, BUG, STEEL, FIRE, GROUND, ROCK).contains(DRAGON, FLYING, WATER);
    }

    @Test
    void suggestsTheMoveTypesThatHitMostUncoveredTypesFirst() {
        TeamCoverage coverage = TeamCoverage.of(team(List.of(FLAMETHROWER, SURF)), GEN4);

        TeamCoverage.Suggestion best = coverage.suggestions().getFirst();
        assertThat(best.hits()).isNotEmpty().allMatch(coverage.unhit()::contains);
        assertThat(coverage.suggestions()).isSortedAccordingTo((a, b) -> b.hits().size() - a.hits().size());
        assertThat(coverage.suggestions()).extracting(TeamCoverage.Suggestion::moveType).doesNotContain(FIRE, WATER);
    }

    @Test
    void duplicateTypesAndStatusMovesCountOnce() {
        TeamCoverage coverage = TeamCoverage.of(team(List.of(PSYBEAM, PSYCHIC_MOVE, GROWL)), GEN4);

        assertThat(coverage.moveTypes()).containsExactly(PSYCHIC);
        assertThat(coverage.unhit()).doesNotContain(FIGHTING, POISON);
    }

    @Test
    void anEmptyTeamCoversNothing() {
        TeamCoverage coverage = TeamCoverage.of(Team.EMPTY, GEN4);

        assertThat(coverage.moveTypes()).isEmpty();
        assertThat(coverage.unhit()).containsExactlyElementsOf(GEN4.typeChart().types());
        assertThat(coverage.suggestions()).isNotEmpty();
    }

    private static Team team(List<Integer> moves) {
        return new Team(List.of(new Team.Member(PokemonId.of(25), moves.subList(0, Math.min(4, moves.size())))));
    }
}
