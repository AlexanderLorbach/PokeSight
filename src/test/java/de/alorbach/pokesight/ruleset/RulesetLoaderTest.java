package de.alorbach.pokesight.ruleset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.alorbach.pokesight.ruleset.PokemonType.*;
import static org.assertj.core.api.Assertions.assertThat;

class RulesetLoaderTest {

    private final Ruleset gen4 = RulesetLoader.load("gen4");

    @Test
    void containsAllSinnohSpecies() {
        assertThat(gen4.species()).hasSize(493);
        assertThat(gen4.findSpecies(493)).map(s -> s.name(Language.EN)).contains("Arceus");
        assertThat(gen4.findSpecies(1)).map(s -> s.name(Language.DE)).contains("Bisasam");
    }

    @Test
    void usesTypesOfThatGeneration() {
        assertThat(gen4.typeChart().types()).doesNotContain(FAIRY);
        assertThat(gen4.findSpecies(35)).map(Species::types).contains(List.of(NORMAL));              // Clefairy, Fairy since Gen 6
        assertThat(gen4.findSpecies(81)).map(Species::types).contains(List.of(ELECTRIC, STEEL));     // Magnemite
    }

    @Test
    void loadsAlternateFormsByGameFormIndex() {
        Species shaymin = gen4.findSpecies(492).orElseThrow();
        assertThat(shaymin.form(0).types()).containsExactly(GRASS);
        assertThat(shaymin.form(1).types()).containsExactly(GRASS, FLYING);
        assertThat(shaymin.form(1).name(Language.EN)).isEqualTo("Sky Shaymin");

        Species arceus = gen4.findSpecies(493).orElseThrow();
        assertThat(arceus.form(8).types()).containsExactly(STEEL);
        assertThat(arceus.form(9).types()).containsExactly(NORMAL); // ??? form, Gen 4 only
        assertThat(arceus.form(10).types()).containsExactly(FIRE);
        assertThat(arceus.form(17).types()).containsExactly(DARK);
        assertThat(gen4.findSpecies(479).orElseThrow().forms()).isEmpty(); // Rotom: appliance types only from Gen 5
    }

    @Test
    void usesTypeChartOfThatGeneration() {
        assertThat(gen4.typeChart().multiplier(GHOST, STEEL)).isEqualTo(0.5); // neutral since Gen 6
        assertThat(gen4.typeChart().multiplier(DARK, STEEL)).isEqualTo(0.5);
        assertThat(gen4.typeChart().multiplier(ELECTRIC, GROUND)).isZero();
        assertThat(gen4.typeChart().multiplier(FIRE, FIRE)).isEqualTo(0.5);
    }

    @Test
    void usesMovePowerAndTypeOfThatGeneration() {
        Move thunderbolt = gen4.findMove(85).orElseThrow();
        assertThat(thunderbolt.name(Language.DE)).isEqualTo("Donnerblitz");
        assertThat(thunderbolt.type()).isEqualTo(ELECTRIC);
        assertThat(thunderbolt.power()).isEqualTo(95);                       // 90 since Gen 6
        assertThat(thunderbolt.category()).isEqualTo(Move.Category.SPECIAL);
        assertThat(gen4.findMove(56)).map(Move::power).contains(120);        // Hydro Pump: 110 since Gen 6
        assertThat(gen4.findMove(44)).map(Move::category).contains(Move.Category.PHYSICAL); // Bite: physical since Gen 4
    }

    @Test
    void containsOnlyDamagingMovesWithFixedPowerOfThatGeneration() {
        assertThat(gen4.findMove(204)).isEmpty(); // Charm: status move
        assertThat(gen4.findMove(69)).isEmpty();  // Seismic Toss: no fixed power
        assertThat(gen4.findMove(585)).isEmpty(); // Moonblast: Gen 6
        // Found by cross-checking with Pokémon Showdown's Gen 4 data:
        assertThat(gen4.findMove(237)).isEmpty(); // Hidden Power: type and power depend on the user
        assertThat(gen4.findMove(386)).isEmpty(); // Punishment: power grows with the target's boosts
        assertThat(gen4.findMove(295)).map(Move::power).contains(70); // Luster Purge: 95 only since Gen 7
        assertThat(gen4.moves().values()).allSatisfy(move -> assertThat(move.type()).isNotEqualTo(FAIRY));
    }
}
