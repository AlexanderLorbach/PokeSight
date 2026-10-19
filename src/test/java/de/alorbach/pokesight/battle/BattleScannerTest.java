package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.Word;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BattleScannerTest {

    private static final BufferedImage SCREEN = new BufferedImage(256, 192, BufferedImage.TYPE_INT_RGB);

    private final FakeRecognizer recognizer = new FakeRecognizer();
    private final NameMatcher matcher = new NameMatcher(RulesetLoader.load("gen4"), Language.EN);
    private final BattleScanner scanner = new BattleScanner(recognizer, matcher, false);

    @Test
    void gamesWithCapitalNamesIgnoreMixedCaseText() {
        BattleScanner capitals = new BattleScanner(recognizer, matcher, true);
        recognizer.showLines("public void Gengar() Lv32", "GENGAR Lv32");

        assertThat(capitals.scan(SCREEN)).singleElement().satisfies(sighting -> {
            assertThat(sighting.text()).isEqualTo("GENGAR Lv32");
            assertThat(sighting.pokemon()).contains(PokemonId.of(94));
        });
    }

    @Test
    void recognizesMostlyCapitalWords() {
        assertThat(BattleScanner.isMostlyCapitals("GENGAR")).isTrue();
        assertThat(BattleScanner.isMostlyCapitals("ELEGTABUZ2")).isTrue();
        assertThat(BattleScanner.isMostlyCapitals("MR.")).isTrue();
        assertThat(BattleScanner.isMostlyCapitals("Gengar")).isFalse();
        assertThat(BattleScanner.isMostlyCapitals("gengar")).isFalse();
        assertThat(BattleScanner.isMostlyCapitals("123")).isFalse();
    }

    @Test
    void readsNameAndLevel() {
        recognizer.showLines("GENGAR Lv32");

        assertThat(scanner.scan(SCREEN)).containsExactly(
                new Sighting("GENGAR Lv32", Optional.of(PokemonId.of(94)), OptionalInt.of(32)));
    }

    @Test
    void readsBothOpponentsOfADoubleBattle() {
        recognizer.showLines("ZUBAT Lv. 12", "GEODUDE L 14");

        assertThat(scanner.scan(SCREEN))
                .extracting(Sighting::pokemon, Sighting::level)
                .containsExactly(
                        tuple(Optional.of(PokemonId.of(41)), OptionalInt.of(12)),
                        tuple(Optional.of(PokemonId.of(74)), OptionalInt.of(14)));
    }

    @Test
    void toleratesNoiseWordsAndSplitNames() {
        recognizer.showLines("~ MR. MIME 9 Lu45");

        assertThat(scanner.scan(SCREEN)).singleElement().satisfies(sighting -> {
            assertThat(sighting.pokemon()).contains(PokemonId.of(122));
            assertThat(sighting.level()).hasValue(45); // the stray "9" has no level sign
        });
    }

    @Test
    void ignoresNumbersWithoutLevelSign() {
        recognizer.showLines("ELEGTABUZ2 29"); // real OCR output for "ELECTABUZZ Lv39"

        assertThat(scanner.scan(SCREEN)).singleElement().satisfies(sighting -> {
            assertThat(sighting.pokemon()).contains(PokemonId.of(125));
            assertThat(sighting.level()).isEmpty();
        });
    }

    @Test
    void keepsUnreadableNameBoxSoTheUserCanCorrectIt() {
        recognizer.showLines("QWXZRT Lv20");

        assertThat(scanner.scan(SCREEN)).containsExactly(new Sighting("QWXZRT Lv20", Optional.empty(), OptionalInt.of(20)));
    }

    @Test
    void ignoresTextWithoutNameOrLevel() {
        recognizer.showLines("What will", "FIGHT BAG RUN");

        assertThat(scanner.scan(SCREEN)).isEmpty();
    }

    @Test
    void groupsWordsIntoLinesByVerticalPosition() {
        List<Word> words = List.of(
                new Word("Lv5", 120, 2, 160, 22),
                new Word("STARLY", 0, 0, 100, 20),
                new Word("BIDOOF", 0, 50, 100, 70));

        assertThat(BattleScanner.lines(words))
                .extracting(line -> line.stream().map(Word::text).toList())
                .containsExactly(List.of("STARLY", "Lv5"), List.of("BIDOOF"));
    }
}
