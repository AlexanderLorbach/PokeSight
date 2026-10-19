package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static de.alorbach.pokesight.ruleset.PokemonType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MatchupCalculatorTest {

    private static final PokemonId PIKACHU = PokemonId.of(25);
    private static final PokemonId MONFERNO = PokemonId.of(391);
    private static final int THUNDERBOLT = 85;  // Electric, 95
    private static final int QUICK_ATTACK = 98; // Normal, 40
    private static final int EMBER = 52;        // Fire, 40
    private static final int MACH_PUNCH = 183;  // Fighting, 40

    private final MatchupCalculator calculator = new MatchupCalculator(RulesetLoader.load("gen4"));
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
    void usesPureTypesWithoutAbilities() {
        Matchup gengar = analyze(94, 0, Language.EN).matchup(); // Levitate is not taken into account

        assertThat(gengar.typesWith(0)).containsExactlyInAnyOrder(NORMAL, FIGHTING);
        assertThat(gengar.typesWith(2)).containsExactlyInAnyOrder(GROUND, GHOST, PSYCHIC, DARK);
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
    void ranksTheTeamsMovesByPowerEffectivenessAndStab() {
        List<MoveOption> vsGyarados = analyze(130, 0, Language.EN).bestMoves();

        assertThat(vsGyarados).extracting(MoveOption::moveId, MoveOption::effectiveness, MoveOption::stab, MoveOption::score)
                .containsExactly(
                        tuple(THUNDERBOLT, 4.0, true, 570.0),  // 95 x 4 x 1.5 (Pikachu is Electric)
                        tuple(QUICK_ATTACK, 1.0, false, 40.0), // Normal move: no STAB for Pikachu
                        tuple(EMBER, 0.5, true, 30.0),         // Monferno is Fire/Fighting
                        tuple(MACH_PUNCH, 0.5, true, 30.0));   // Fighting vs. Flying: 1/2
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
