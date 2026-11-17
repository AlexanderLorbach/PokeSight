package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.catching.Pokedex;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A team member read in battle under a later stage's name, on the real evolution data. */
class TeamTest {

    private static final Pokedex POKEDEX = Pokedex.load();

    private static final int PIKACHU = 25;
    private static final int RAICHU = 26;
    private static final int EEVEE = 133;
    private static final int VAPOREON = 134;
    private static final int JOLTEON = 135;
    private static final int STARLY = 396;
    private static final int CHIMCHAR = 390;
    private static final int MONFERNO = 391;
    private static final int INFERNAPE = 392;
    private static final List<Integer> MOVES = List.of(52, 10);

    @Test
    void theMemberBecomesItsEvolutionAndKeepsMovesAndSlot() {
        Team team = team(STARLY, CHIMCHAR, PIKACHU);

        assertThat(team.withEvolution(PokemonId.of(MONFERNO), POKEDEX, List.of()))
                .contains(team(STARLY, MONFERNO, PIKACHU));
    }

    @Test
    void anEvolvedMemberKeepsItsNickname() {
        Team team = new Team(List.of(new Team.Member(PokemonId.of(CHIMCHAR), MOVES, "Blaze")));

        assertThat(team.withEvolution(PokemonId.of(MONFERNO), POKEDEX, List.of()).orElseThrow().members().getFirst())
                .isEqualTo(new Team.Member(PokemonId.of(MONFERNO), MOVES, "Blaze"));
        assertThat(team.nicknames()).containsExactly(org.assertj.core.api.Assertions.entry("Blaze", PokemonId.of(CHIMCHAR)));
    }

    @Test
    void skippedStagesAndBranchesCount() {
        assertThat(team(CHIMCHAR).withEvolution(PokemonId.of(INFERNAPE), POKEDEX, List.of())).contains(team(INFERNAPE));
        assertThat(team(EEVEE).withEvolution(PokemonId.of(VAPOREON), POKEDEX, List.of())).contains(team(VAPOREON));
    }

    @Test
    void theEvolvedFormIsTheOneRead() {
        PokemonId alolanRaichu = new PokemonId(RAICHU, 1);

        assertThat(team(PIKACHU).withEvolution(alolanRaichu, POKEDEX, List.of()))
                .map(evolved -> evolved.members().getFirst().pokemon())
                .contains(alolanRaichu);
    }

    @Test
    void nothingChangesForAMemberOrAnUnrelatedPokemon() {
        assertThat(team(CHIMCHAR).withEvolution(PokemonId.of(CHIMCHAR), POKEDEX, List.of())).isEmpty();
        assertThat(team(CHIMCHAR).withEvolution(PokemonId.of(VAPOREON), POKEDEX, List.of())).isEmpty();
        // An earlier stage is not a de-evolution.
        assertThat(team(MONFERNO).withEvolution(PokemonId.of(CHIMCHAR), POKEDEX, List.of())).isEmpty();
    }

    @Test
    void nothingChangesIfTheEvolutionIsAlreadyInTheTeam() {
        assertThat(team(CHIMCHAR, MONFERNO).withEvolution(PokemonId.of(MONFERNO), POKEDEX, List.of())).isEmpty();
        assertThat(team(PokemonId.of(CHIMCHAR), new PokemonId(MONFERNO, 1)).withEvolution(PokemonId.of(MONFERNO), POKEDEX, List.of())).isEmpty();
    }

    @Test
    void nothingChangesIfItIsUnclearWhichMemberEvolved() {
        assertThat(team(CHIMCHAR, CHIMCHAR).withEvolution(PokemonId.of(MONFERNO), POKEDEX, List.of())).isEmpty();
        assertThat(team(CHIMCHAR, MONFERNO).withEvolution(PokemonId.of(INFERNAPE), POKEDEX, List.of())).isEmpty();
    }

    @Test
    void aDeclinedEvolutionAndItsLaterStagesAreNotAppliedAgain() {
        List<Team.Evolution> declined = List.of(new Team.Evolution(CHIMCHAR, MONFERNO));

        assertThat(team(CHIMCHAR).withEvolution(PokemonId.of(MONFERNO), POKEDEX, declined)).isEmpty();
        assertThat(team(CHIMCHAR).withEvolution(PokemonId.of(INFERNAPE), POKEDEX, declined)).isEmpty();
        // Declining Eevee into Vaporeon says nothing about Eevee's other evolutions.
        assertThat(team(EEVEE).withEvolution(PokemonId.of(JOLTEON), POKEDEX, List.of(new Team.Evolution(EEVEE, VAPOREON))))
                .contains(team(JOLTEON));
    }

    @Test
    void changingAMemberBackToAnEarlierStageUndoesTheEvolution() {
        Team evolved = team(STARLY, MONFERNO);

        assertThat(evolved.undoneEvolutions(team(STARLY, CHIMCHAR), POKEDEX)).containsExactly(new Team.Evolution(CHIMCHAR, MONFERNO));
        assertThat(team(INFERNAPE).undoneEvolutions(team(CHIMCHAR), POKEDEX)).containsExactly(new Team.Evolution(CHIMCHAR, INFERNAPE));
    }

    @Test
    void otherEditsUndoNoEvolution() {
        Team team = team(STARLY, MONFERNO);

        assertThat(team.undoneEvolutions(team(STARLY, PIKACHU), POKEDEX)).isEmpty();   // replaced by another line
        assertThat(team.undoneEvolutions(team(STARLY, INFERNAPE), POKEDEX)).isEmpty(); // evolved further by hand
        assertThat(team.undoneEvolutions(team(STARLY), POKEDEX)).isEmpty();            // removed
        assertThat(team.undoneEvolutions(team, POKEDEX)).isEmpty();
    }

    private static Team team(int... species) {
        return team(Arrays.stream(species).mapToObj(PokemonId::of).toArray(PokemonId[]::new));
    }

    private static Team team(PokemonId... pokemon) {
        return new Team(Arrays.stream(pokemon).map(member -> new Team.Member(member, MOVES)).toList());
    }
}
