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
    void loadsBaseStatsAndAbilitiesOfThatGeneration() {
        Species.Form gengar = gen4.findSpecies(94).orElseThrow().form(0);
        assertThat(gengar.stat(Species.Stat.SPECIAL_ATTACK)).isEqualTo(130);
        assertThat(gengar.abilities()).containsExactly("levitate"); // Cursed Body since Gen 7
        assertThat(RulesetLoader.load("gen7").findSpecies(94).orElseThrow().abilities()).containsExactly("cursed-body");
        assertThat(gen4.generation()).isEqualTo(4);
        assertThat(gen4.abilityName("levitate", Language.DE)).isEqualTo("Schwebe");
    }

    @Test
    void genOneHasOneSpecialStatAndNoAbilities() {
        Species.Form alakazam = RulesetLoader.load("gen1").findSpecies(65).orElseThrow().form(0);
        assertThat(alakazam.stat(Species.Stat.SPECIAL_ATTACK)).isEqualTo(135);
        assertThat(alakazam.stat(Species.Stat.SPECIAL_DEFENSE)).isEqualTo(135); // 85 Sp. Def since Gen 2
        assertThat(alakazam.abilities()).isEmpty();
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
        assertThat(gen4.findMove(295)).map(Move::power).contains(70); // Luster Purge: 95 only since Scarlet/Violet
        assertThat(gen4.moves().values()).allSatisfy(move -> assertThat(move.type()).isNotEqualTo(FAIRY));
    }

    @Test
    void loadsEveryGeneration() {
        assertThat(List.of("gen1", "gen2", "gen3", "gen5", "gen6", "gen7", "lets-go", "gen8", "gen9"))
                .extracting(id -> RulesetLoader.load(id).species().size())
                .containsExactly(151, 251, 386, 649, 721, 809, 809, 905, 1025);
    }

    @Test
    void usesGen1Rules() {
        Ruleset gen1 = RulesetLoader.load("gen1");
        assertThat(gen1.typeChart().types()).doesNotContain(DARK, STEEL, FAIRY);
        assertThat(gen1.typeChart().multiplier(GHOST, PSYCHIC)).isZero();        // the Gen 1 bug
        assertThat(gen1.typeChart().multiplier(BUG, POISON)).isEqualTo(2);
        assertThat(gen1.typeChart().multiplier(ICE, FIRE)).isEqualTo(1);
        assertThat(gen1.findSpecies(81)).map(Species::types).contains(List.of(ELECTRIC)); // Magnemite before Steel
        assertThat(gen1.findMove(44)).map(Move::type).contains(NORMAL);           // Bite: Dark since Gen 2
        assertThat(gen1.findMove(7)).map(Move::category).contains(Move.Category.SPECIAL); // Fire Punch: by type until Gen 3
    }

    @Test
    void usesFairyTypeChartSinceGen6() {
        Ruleset gen6 = RulesetLoader.load("gen6");
        assertThat(gen6.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(gen6.typeChart().multiplier(GHOST, STEEL)).isEqualTo(1);
        assertThat(gen6.findSpecies(35)).map(Species::types).contains(List.of(FAIRY)); // Clefairy
        assertThat(gen6.findMove(594)).map(Move::category).contains(Move.Category.PHYSICAL); // Water Shuriken, special since Gen 7
    }

    @Test
    void usesMovesOfTheVersionGroup() {
        // Let's Go doubled Absorb (20 -> 40); Sun/Moon and Sword/Shield have 20.
        assertThat(RulesetLoader.load("gen7").findMove(71)).map(Move::power).contains(20);
        assertThat(RulesetLoader.load("lets-go").findMove(71)).map(Move::power).contains(40);
        assertThat(RulesetLoader.load("gen8").findMove(803)).map(Move::power).contains(70); // Grassy Glide: 55 since SV
        assertThat(RulesetLoader.load("gen9").findMove(803)).map(Move::power).contains(55);
        assertThat(RulesetLoader.load("gen9").findMove(295)).map(Move::power).contains(95); // Luster Purge
        assertThat(RulesetLoader.load("gen7").findMove(658)).isEmpty(); // Catastropika: Z-Move
        assertThat(RulesetLoader.load("gen8").findMove(757)).isEmpty(); // Max Flare: Max Move
    }

    @Test
    void marksRegionalForms() {
        Species meowth = RulesetLoader.load("gen8").findSpecies(52).orElseThrow();
        assertThat(meowth.regionalForm("alola")).isEqualTo(1);
        assertThat(meowth.form(meowth.regionalForm("galar")).types()).containsExactly(STEEL);
        assertThat(meowth.regionalForm("paldea")).isZero();
        assertThat(meowth.regionalForm(null)).isZero();
        Species tauros = RulesetLoader.load("gen9").findSpecies(128).orElseThrow();
        assertThat(tauros.form(tauros.regionalForm("paldea")).name(Language.DE)).isEqualTo("Paldea-Tauros (Gefechtvariante)");
    }
}
