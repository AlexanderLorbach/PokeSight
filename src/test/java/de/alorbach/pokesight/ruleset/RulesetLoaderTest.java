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
    void loadsTheRomHackRulesetFromTheHacksSources() {
        Ruleset heartfeltGold = RulesetLoader.load("heartfelt-gold");

        assertThat(heartfeltGold.species()).hasSize(1025);
        assertThat(heartfeltGold.generation()).isEqualTo(9);
        assertThat(heartfeltGold.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(heartfeltGold.typeChart().multiplier(GHOST, STEEL)).isEqualTo(1);
        assertThat(heartfeltGold.findSpecies(35)).map(Species::types).contains(List.of(FAIRY)); // Clefairy
        assertThat(heartfeltGold.findSpecies(985)).map(s -> s.name(Language.EN)).contains("ScreamTail"); // in-game name
        assertThat(heartfeltGold.findSpecies(122).orElseThrow().form(1).types()).containsExactly(ICE, PSYCHIC); // Galarian Mr. Mime
        assertThat(heartfeltGold.findSpecies(658)).map(Species::abilities).contains(List.of("torrent", "battle-bond", "protean"));
        // The hack's "Champions" move changes and its own move data
        assertThat(heartfeltGold.findMove(779)).map(Move::type).contains(STEEL);                       // Snap Trap
        assertThat(heartfeltGold.findMove(163)).map(Move::power).contains(80);                         // Slash
        assertThat(heartfeltGold.findMove(550)).map(Move::category).contains(Move.Category.SPECIAL);  // Bolt Strike
        assertThat(heartfeltGold.findMove(757)).isEmpty(); // Max Flare
    }

    @Test
    void loadsTheOdysseyRulesetWithItsOwnTypeAndPokemon() {
        Ruleset odyssey = RulesetLoader.load("odyssey");

        assertThat(odyssey.species()).hasSize(404);
        assertThat(odyssey.typeChart().types()).contains(AETHER).doesNotContain(FAIRY);
        assertThat(odyssey.typeChart().multiplier(POISON, WATER)).isEqualTo(2);   // the hack's own chart
        assertThat(odyssey.typeChart().multiplier(DARK, AETHER)).isEqualTo(2);
        assertThat(odyssey.typeChart().multiplier(WATER, AETHER)).isEqualTo(0.5);
        assertThat(odyssey.findSpecies(35)).map(Species::types).contains(List.of(AETHER)); // Clefairy
        assertThat(odyssey.findSpecies(20)).map(s -> s.name(Language.EN)).contains("Ratreecate"); // Etrian Raticate
        assertThat(odyssey.findSpecies(5201)).map(Species::sprite).contains(Species.NO_SPRITE);    // Gorochu, the hack's own
        assertThat(odyssey.findSpecies(5150)).map(Species::sprite).contains("352");                // B.B. Kecleon
        assertThat(odyssey.findMove(129)).map(Move::type).contains(AETHER);                         // Swift
        assertThat(odyssey.findMove(246)).map(Move::name).map(name -> name.get(Language.EN)).contains("Ancientpower");
        assertThat(odyssey.findMove(585)).isEmpty(); // Moonblast: no Fairy type
    }

    @Test
    void loadsTheRoweRulesetWithItsChangedPokemonAndMoves() {
        Ruleset rowe = RulesetLoader.load("rowe");

        assertThat(rowe.species()).hasSize(1025);
        assertThat(rowe.generation()).isEqualTo(8);
        assertThat(rowe.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(rowe.typeChart().multiplier(GHOST, STEEL)).isEqualTo(1);
        assertThat(rowe.findSpecies(24)).map(Species::types).contains(List.of(POISON, DARK));   // Arbok, changed by the hack
        assertThat(rowe.findSpecies(986)).map(s -> s.name(Language.EN)).contains("BruteBonnet"); // in-game name
        assertThat(rowe.findSpecies(1)).map(Species::abilities).contains(List.of("overgrow", "solar-power", "chlorophyll"));
        assertThat(rowe.findSpecies(19)).map(Species::sprite).contains("rattata/anim_front");
        assertThat(rowe.findSpecies(19).orElseThrow().form(1).sprite()).isEqualTo("rattata/alola/front");
        assertThat(rowe.findMove(15)).map(Move::type).contains(STEEL);  // Cut
        assertThat(rowe.findMove(141)).map(Move::power).contains(80);   // Leech Life (B_UPDATED_MOVE_DATA GEN_8)
        assertThat(rowe.findMove(185)).map(Move::type).contains(DARK);  // "Faint Attack" = Feint Attack
        assertThat(rowe.findMove(20824)).map(Move::name).map(name -> name.get(Language.EN)).contains("Neutral Pulse"); // the hack's own
        assertThat(rowe.findMove(20825)).isEmpty(); // Seasonal Beam: type and category change
    }

    @Test
    void loadsTheUnboundRulesetWithItsChangedPokemonAndMoves() {
        Ruleset unbound = RulesetLoader.load("unbound");

        assertThat(unbound.species()).hasSize(905); // up to Gen 8
        assertThat(unbound.generation()).isEqualTo(8);
        assertThat(unbound.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(unbound.findSpecies(823)).map(s -> s.name(Language.EN)).contains("Corvknight"); // in-game name
        assertThat(unbound.findSpecies(669)).map(s -> s.name(Language.EN)).contains("Flabébé");
        assertThat(unbound.findSpecies(19)).map(Species::abilities).contains(List.of("strong-jaw", "guts", "hustle")); // changed
        assertThat(unbound.findSpecies(898).orElseThrow().form(1).abilities()).containsExactly("as-one-glastrier");
        assertThat(unbound.findSpecies(19)).map(Species::sprite).contains("frontspr/gFrontSprite019Rattata");
        assertThat(unbound.findSpecies(19).orElseThrow().form(1).sprite()).isEqualTo("frontspr/gFrontSprite1020RattataA");
        assertThat(unbound.findMove(19)).map(Move::power).contains(100);  // Fly: CFRU's #ifdef UNBOUND
        assertThat(unbound.findMove(173)).map(Move::power).contains(80);  // Snore
        assertThat(unbound.findMove(20355)).map(Move::name).map(name -> name.get(Language.EN)).contains("Leech Fang"); // the hack's own
        assertThat(unbound.findMove(827)).isEmpty(); // Gen 9 moves are not in the hack
    }

    @Test
    void loadsTheGlazedRulesetFromItsDocumentation() {
        Ruleset glazed = RulesetLoader.load("glazed");

        assertThat(glazed.species()).hasSize(411); // the wiki's slots: Gen 1-6 Pokémon replacing Emerald ones
        assertThat(glazed.generation()).isEqualTo(3); // Emerald's ability engine
        assertThat(glazed.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(glazed.typeChart().multiplier(GHOST, STEEL)).isEqualTo(1); // "Steel no longer resists Dark and Ghost"
        assertThat(glazed.findSpecies(43)).isEmpty();                         // Oddish's slot holds Deino
        assertThat(glazed.findSpecies(633)).map(Species::types).contains(List.of(DARK, DRAGON));
        assertThat(glazed.findSpecies(571)).map(Species::stats).contains(List.of(60, 120, 60, 120, 60, 105)); // Zoroark +15 Atk
        assertThat(glazed.findSpecies(94)).map(Species::abilities).contains(List.of("levitate")); // Emerald's Gengar
        assertThat(glazed.findSpecies(453)).map(Species::abilities).contains(List.of()); // Croagunk: no Gen 3 ability
        assertThat(glazed.findSpecies(5269)).map(Species::sprite).contains("10033");     // Mega Venusaur's own slot
        assertThat(glazed.findMove(229)).map(Move::power).contains(50);  // Rapid Spin (Beta 8.6.2)
        assertThat(glazed.findMove(585)).map(Move::type).contains(FAIRY); // Moonblast, listed in the move document
        assertThat(glazed.findMove(394)).isPresent();                    // Flare Blitz: listed
        assertThat(glazed.findMove(412)).isEmpty();                      // Energy Ball: a later move not in the game
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
