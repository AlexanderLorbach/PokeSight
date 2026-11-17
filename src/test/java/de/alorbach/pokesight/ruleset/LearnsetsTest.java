package de.alorbach.pokesight.ruleset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The generated learnsets/<id>.yaml files. */
class LearnsetsTest {

    private static final int PIKACHU = 25;
    private static final int RAICHU = 26;
    private static final int ALOLAN_FORM = 1;
    private static final int THUNDERBOLT = 85;
    private static final int PSYCHIC = 94;
    private static final int BULBASAUR = 1;
    private static final int PETAL_DANCE = 80;

    @Test
    void namesTheMachineThatTeachesAMove() {
        Learnsets platinum = Learnsets.load("platinum");

        assertThat(platinum.machine(THUNDERBOLT)).contains("TM24");
        assertThat(platinum.find(PIKACHU, 0).orElseThrow().ways().get(THUNDERBOLT))
                .contains(new Learnset.Way(Learnset.Method.LEVEL, 26), new Learnset.Way(Learnset.Method.MACHINE, 0));
    }

    @Test
    void aRegionalFormHasItsOwnLearnset() {
        Learnsets sunMoon = Learnsets.load("sun-moon");

        // Alolan Raichu learns Psychic when it evolves; Kantonian Raichu never does.
        assertThat(sunMoon.find(RAICHU, ALOLAN_FORM).orElseThrow().ways().get(PSYCHIC))
                .startsWith(new Learnset.Way(Learnset.Method.LEVEL, 0));
        assertThat(sunMoon.find(RAICHU, 0).orElseThrow().ways()).doesNotContainKey(PSYCHIC);
    }

    @Test
    void anEggMoveIsOnlyAnEggMove() {
        // Bulbasaur learns Petal Dance only by breeding in Platinum.
        assertThat(Learnsets.load("platinum").find(BULBASAUR, 0).orElseThrow().ways().get(PETAL_DANCE))
                .containsExactly(new Learnset.Way(Learnset.Method.EGG, 0));
    }

    @Test
    void aSpeciesTheGameLacksHasNoLearnset() {
        // Legends: Arceus has the Hisui PokÃ©dex: Pikachu is in it, Charmander is not.
        Learnsets arceus = Learnsets.load("legends-arceus");

        assertThat(arceus.find(PIKACHU, 0)).isPresent();
        assertThat(arceus.find(4, 0)).isEmpty();
    }
}
