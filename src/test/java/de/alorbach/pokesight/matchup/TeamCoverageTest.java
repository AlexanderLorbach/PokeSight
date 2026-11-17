package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Learnsets;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
    private static final int EMBER = 52;
    private static final int BITE = 44;
    private static final PokemonId CHARMANDER = PokemonId.of(4);
    private static final PokemonId SQUIRTLE = PokemonId.of(7);
    private static final PokemonId PIKACHU = PokemonId.of(25);
    private static final PokemonId MAGIKARP = PokemonId.of(129);
    private static final int TACKLE = 33;
    private static final int THUNDERBOLT = 85;
    private static final int DIG = 91;
    private static final int BOUNCE = 340;
    private static final MovePool PLATINUM = new MovePool(Learnsets.load("platinum"), GEN4);

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

    @Test
    void aMoveWhoseTypesAnotherMoveAlsoHitsCanMakeWayWithoutLosingCoverage() {
        // Ember and Flamethrower hit the same types; Surf and Bite each cover types no other move does.
        Team team = new Team(List.of(new Team.Member(CHARMANDER, List.of(FLAMETHROWER, SURF, BITE)),
                new Team.Member(SQUIRTLE, List.of(EMBER))));

        TeamCoverage coverage = TeamCoverage.of(team, GEN4);

        TeamCoverage.Suggestion ground = suggestion(coverage, GROUND);
        assertThat(ground.hits()).contains(ELECTRIC);
        // Squirtle's Ember first: no STAB, while Charmander's Flamethrower has it.
        assertThat(ground.replacements()).containsExactly(
                new TeamCoverage.Replacement(SQUIRTLE, EMBER, List.of(), Optional.empty()),
                new TeamCoverage.Replacement(CHARMANDER, FLAMETHROWER, List.of(), Optional.empty()));
        assertThat(ground.emptySlots()).extracting(TeamCoverage.Slot::member).containsExactly(CHARMANDER, SQUIRTLE);
    }

    @Test
    void withoutAFreeSwapTheMoveThatCostsTheFewestTypesIsNamedWithWhatItCosts() {
        TeamCoverage coverage = TeamCoverage.of(team(List.of(FLAMETHROWER, SURF)), GEN4);

        // A Ground move hits Fire and Rock as Surf does, but not Ground; Flamethrower alone hits Grass, Ice and Bug.
        assertThat(suggestion(coverage, GROUND).replacements())
                .containsExactly(new TeamCoverage.Replacement(PIKACHU, SURF, List.of(GROUND), Optional.empty()));
    }

    @Test
    void aMemberWithFourMovesHasNoFreeSlot() {
        TeamCoverage coverage = TeamCoverage.of(team(List.of(FLAMETHROWER, SURF, EMBER, BITE)), GEN4);

        assertThat(coverage.suggestions()).allSatisfy(suggestion -> assertThat(suggestion.emptySlots()).isEmpty());
    }

    @Test
    void withLearnsetsAMoveOnlyGoesToMembersThatLearnOneOfItsType() {
        // Platinum: Pikachu learns Dig (TM28), Magikarp no Ground move at all.
        Team team = new Team(List.of(new Team.Member(PIKACHU, List.of(THUNDERBOLT)), new Team.Member(MAGIKARP, List.of(TACKLE))));

        TeamCoverage coverage = TeamCoverage.of(team, GEN4, Optional.of(PLATINUM));

        TeamCoverage.Suggestion ground = suggestion(coverage, GROUND);
        assertThat(coverage.learnsetsKnown()).isTrue();
        assertThat(ground.emptySlots()).extracting(TeamCoverage.Slot::member).containsExactly(PIKACHU);
        assertThat(ground.emptySlots().getFirst().learn()).map(MovePool.Learnable::moveId).contains(DIG);
        assertThat(ground.replacements()).extracting(TeamCoverage.Replacement::member).containsOnly(PIKACHU);
    }

    @Test
    void suggestionsNoMemberCanLearnComeLast() {
        // Magikarp's only other damaging move is Bounce (Flying, tutor), which hits Grass, Fighting and Bug.
        Team team = new Team(List.of(new Team.Member(MAGIKARP, List.of(TACKLE))));

        TeamCoverage coverage = TeamCoverage.of(team, GEN4, Optional.of(PLATINUM));

        assertThat(coverage.suggestions().getFirst().moveType()).isEqualTo(FLYING);
        assertThat(coverage.suggestions().getFirst().emptySlots().getFirst().learn()).map(MovePool.Learnable::moveId).contains(BOUNCE);
        assertThat(coverage.suggestions().subList(1, coverage.suggestions().size()))
                .noneMatch(TeamCoverage.Suggestion::placeable);
    }

    @Test
    void aMemberTheLearnsetsLackCountsAsAbleToLearnAnything() {
        // Sylveon (Gen 6) in a Platinum game: Platinum Together's MODERN POKéMON.
        Team team = new Team(List.of(new Team.Member(PokemonId.of(700), List.of(TACKLE))));

        TeamCoverage coverage = TeamCoverage.of(team, GEN4, Optional.of(PLATINUM));

        assertThat(coverage.suggestions()).allMatch(TeamCoverage.Suggestion::placeable);
        assertThat(coverage.suggestions().getFirst().emptySlots().getFirst().learn()).isEmpty();
    }

    private static TeamCoverage.Suggestion suggestion(TeamCoverage coverage, PokemonType type) {
        return coverage.suggestions().stream().filter(suggestion -> suggestion.moveType() == type).findFirst().orElseThrow();
    }

    private static Team team(List<Integer> moves) {
        return new Team(List.of(new Team.Member(PIKACHU, moves.subList(0, Math.min(4, moves.size())))));
    }
}
