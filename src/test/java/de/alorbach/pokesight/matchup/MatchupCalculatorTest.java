package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.EnemyAnalysis.AbilityNote;
import de.alorbach.pokesight.matchup.EnemyAnalysis.SwitchIn;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static de.alorbach.pokesight.ruleset.PokemonType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.tuple;

class MatchupCalculatorTest {

    private static final PokemonId PIKACHU = PokemonId.of(25);
    private static final PokemonId GENGAR = PokemonId.of(94);
    private static final PokemonId MONFERNO = PokemonId.of(391);
    private static final PokemonId GARCHOMP = PokemonId.of(445);
    private static final int THUNDERBOLT = 85;  // Electric, 95, special
    private static final int QUICK_ATTACK = 98; // Normal, 40, physical
    private static final int EMBER = 52;        // Fire, 40, special
    private static final int MACH_PUNCH = 183;  // Fighting, 40, physical
    private static final int EARTHQUAKE = 89;   // Ground, 100, physical
    private static final Ruleset GEN4 = RulesetLoader.load("gen4");

    private final MatchupCalculator calculator = new MatchupCalculator(GEN4);
    private final Team team = new Team(List.of(
            new Team.Member(PIKACHU, List.of(THUNDERBOLT, QUICK_ATTACK)),
            new Team.Member(MONFERNO, List.of(EMBER, MACH_PUNCH))));

    @Test
    void combinesDualTypes() {
        Matchup gyarados = analyze(130, 0, Language.EN).matchup();

        assertThat(gyarados.typesWith(4)).containsExactly(ELECTRIC);
        assertThat(gyarados.typesWith(2)).containsExactly(ROCK);
        assertThat(gyarados.typesWith(0)).containsExactly(GROUND);
        assertThat(gyarados.typesWith(0.25)).isEmpty();
        assertThat(gyarados.typesWith(1)).isEmpty();
    }

    @Test
    void countsAnAbilityItSurelyHas() {
        EnemyAnalysis gengar = analyze(94, 0, Language.EN); // Gen 4 Gengar can only have Levitate

        assertThat(gengar.abilities()).extracting(AbilityNote::name, AbilityNote::certain).containsExactly(tuple("Levitate", true));
        assertThat(gengar.matchup().typesWith(0)).containsExactlyInAnyOrder(NORMAL, FIGHTING, GROUND);
        assertThat(gengar.matchup().typesWith(2)).containsExactlyInAnyOrder(GHOST, PSYCHIC, DARK);
    }

    @Test
    void namesAbilitiesInTheLanguage() {
        assertThat(analyze(94, 0, Language.DE).abilities()).extracting(AbilityNote::name).containsExactly("Schwebe");
    }

    @Test
    void ignoresAbilitiesInGamesWithoutThem() {
        EnemyAnalysis gengar = new MatchupCalculator(GEN4, false).analyze(GENGAR, Language.EN, team).orElseThrow();

        assertThat(gengar.abilities()).isEmpty();
        assertThat(gengar.matchup().typesWith(2)).contains(GROUND);
    }

    @Test
    void anAbilityItMayHaveIsOnlyAWarning() {
        Team earthquake = new Team(List.of(new Team.Member(GARCHOMP, List.of(EARTHQUAKE))));
        EnemyAnalysis bronzong = calculator.analyze(PokemonId.of(437), Language.EN, earthquake).orElseThrow(); // Levitate or Heatproof

        assertThat(bronzong.abilities()).extracting(AbilityNote::name, AbilityNote::certain)
                .containsExactly(tuple("Levitate", false), tuple("Heatproof", false));
        assertThat(bronzong.matchup().typesWith(2)).containsExactlyInAnyOrder(GROUND, FIRE);
        assertThat(bronzong.bestMoves()).singleElement().satisfies(move -> {
            assertThat(move.effectiveness()).isEqualTo(2.0);
            assertThat(move.caveat()).isEqualTo("Levitate");
        });
    }

    @Test
    void wonderGuardOnlyLetsSuperEffectiveMovesHit() {
        Matchup shedinja = analyze(292, 0, Language.EN).matchup();

        assertThat(shedinja.typesWith(2)).containsExactlyInAnyOrder(FIRE, FLYING, ROCK, GHOST, DARK);
        assertThat(shedinja.typesWith(0)).contains(NORMAL, FIGHTING, WATER, GRASS, ELECTRIC, GROUND);
        assertThat(shedinja.typesWith(0.5)).isEmpty();
    }

    @Test
    void abilitiesWorkAsInTheirGeneration() {
        assertThat(AbilityEffect.of("lightning-rod", 4)).isEmpty(); // only redirected Electric moves until Gen 4
        assertThat(AbilityEffect.of("lightning-rod", 5)).hasValueSatisfying(effect ->
                assertThat(effect.apply(ELECTRIC, 2)).isZero());
        assertThat(AbilityEffect.of("dry-skin", 4)).hasValueSatisfying(effect -> {
            assertThat(effect.apply(FIRE, 2)).isEqualTo(2.5);
            assertThat(effect.apply(WATER, 1)).isZero();
        });
        assertThat(AbilityEffect.of("intimidate", 9)).isEmpty(); // does not change type matchups
    }

    @Test
    void usesTypesAndNameOfAlternateForm() {
        EnemyAnalysis sandyWormadam = analyze(413, 1, Language.DE);

        assertThat(sandyWormadam.name()).isEqualTo("Burmadame (Sand)");
        assertThat(sandyWormadam.types()).containsExactly(BUG, GROUND);
        assertThat(sandyWormadam.matchup().typesWith(0)).containsExactly(ELECTRIC);
        assertThat(sandyWormadam.matchup().typesWith(2)).containsExactlyInAnyOrder(FIRE, WATER, ICE, FLYING);
    }

    @Test
    void resolvesGermanNames() {
        assertThat(analyze(130, 0, Language.DE).name()).isEqualTo("Garados");
    }

    @Test
    void ranksTheTeamsMovesByPowerEffectivenessStabAndStats() {
        List<MoveOption> vsGyarados = analyze(130, 0, Language.EN).bestMoves();

        // Gyarados: Defense 79, Sp. Def 100. Pikachu: Attack 55, Sp. Atk 50. Monferno: Attack 78, Sp. Atk 78.
        assertThat(vsGyarados).extracting(MoveOption::moveId, MoveOption::effectiveness, MoveOption::stab)
                .containsExactly(
                        tuple(THUNDERBOLT, 4.0, true),   // 95 x 4 x 1.5 x 50/100 = 285
                        tuple(MACH_PUNCH, 0.5, true),    // 40 x 0.5 x 1.5 x 78/79 = 29.6
                        tuple(QUICK_ATTACK, 1.0, false), // 40 x 1 x 55/79 = 27.8 (Normal move: no STAB for Pikachu)
                        tuple(EMBER, 0.5, true));        // 40 x 0.5 x 1.5 x 78/100 = 23.4
        assertThat(vsGyarados).extracting(MoveOption::score).satisfiesExactly(
                score -> assertThat(score).isCloseTo(285, offset(0.01)),
                score -> assertThat(score).isCloseTo(29.62, offset(0.01)),
                score -> assertThat(score).isCloseTo(27.85, offset(0.01)),
                score -> assertThat(score).isCloseTo(23.4, offset(0.01)));
        assertThat(vsGyarados.getFirst().user()).isEqualTo(PIKACHU);
    }

    @Test
    void movesWithoutEffectScoreZero() {
        List<MoveOption> vsGengar = analyze(94, 0, Language.EN).bestMoves();

        assertThat(vsGengar.getFirst().moveId()).isEqualTo(THUNDERBOLT);
        assertThat(vsGengar).filteredOn(option -> option.moveId() == QUICK_ATTACK || option.moveId() == MACH_PUNCH)
                .allSatisfy(option -> assertThat(option.score()).isZero());
    }

    @Test
    void ranksSwitchInsByHowTheyTakeItsOwnTypes() {
        List<SwitchIn> vsGyarados = analyze(130, 0, Language.EN).switchIns(); // Water and Flying attacks

        assertThat(vsGyarados).extracting(SwitchIn::member, SwitchIn::worst).containsExactly(
                tuple(PIKACHU, 1.0),   // Water 1x, Flying 1/2x
                tuple(MONFERNO, 2.0)); // Water 2x, Flying 2x
        assertThat(vsGyarados.getFirst().taken()).containsExactly(
                entry(WATER, 1.0), entry(FLYING, 0.5));
    }

    @Test
    void aSwitchInsOwnSureAbilityCounts() {
        Team withGengar = new Team(List.of(new Team.Member(GENGAR, List.of()), new Team.Member(PIKACHU, List.of())));

        List<SwitchIn> vsGarchomp = calculator.analyze(GARCHOMP, Language.EN, withGengar).orElseThrow().switchIns();

        assertThat(vsGarchomp.getFirst().member()).isEqualTo(GENGAR); // Levitate: no Ground damage
        assertThat(vsGarchomp.getFirst().taken()).containsEntry(GROUND, 0.0);
    }

    @Test
    void aTeraTypeReplacesItsTypesButNotItsAttacks() {
        EnemyAnalysis grassGyarados = calculator.analyze(PokemonId.of(130), Language.EN, team, GRASS).orElseThrow();

        assertThat(grassGyarados.tera()).isTrue();
        assertThat(grassGyarados.types()).containsExactly(GRASS);
        assertThat(grassGyarados.matchup().typesWith(2)).containsExactlyInAnyOrder(FIRE, ICE, POISON, FLYING, BUG);
        assertThat(grassGyarados.switchIns().getFirst().taken()).containsOnlyKeys(WATER, FLYING, GRASS);
    }

    @Test
    void skipsUnknownMovesAndPokemon() {
        Team broken = new Team(List.of(new Team.Member(PokemonId.of(9999), List.of(THUNDERBOLT)),
                new Team.Member(PIKACHU, List.of(99999))));

        assertThat(calculator.analyze(PokemonId.of(130), Language.EN, broken)).map(EnemyAnalysis::bestMoves).contains(List.of());
    }

    @Test
    void unknownSpeciesIsSkipped() {
        assertThat(calculator.analyze(PokemonId.of(9999), Language.EN, team)).isEmpty();
    }

    private EnemyAnalysis analyze(int species, int form, Language language) {
        Optional<EnemyAnalysis> analysis = calculator.analyze(new PokemonId(species, form), language, team);
        assertThat(analysis).isPresent();
        return analysis.get();
    }
}
