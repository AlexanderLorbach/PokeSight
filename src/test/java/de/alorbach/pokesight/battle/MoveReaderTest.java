package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MoveReaderTest {

    /** Prefix of the expected moves of a move list in {@code screenshots.tsv}. */
    static final String MOVES = "moves:";

    private static final Ruleset GEN8 = RulesetLoader.load("gen8");

    @Test
    void readsMovesInReadingOrderAndSkipsStatusMoves() {
        // A 2 x 2 fight menu: Thunderbolt | Growl / Quick Attack | PP 30/30
        List<Word> words = List.of(
                new Word("Thunderbolt", 10, 10, 120, 30), new Word("Growl", 300, 10, 360, 30),
                new Word("Quick", 10, 60, 60, 80), new Word("Attack", 66, 60, 130, 80),
                new Word("PP", 300, 60, 320, 80), new Word("30/30", 326, 60, 380, 80));
        TextRecognizer recognizer = recognizing(words);

        List<Integer> moves = new MoveReader(recognizer, GEN8, Language.EN).read(new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB));

        assertThat(moves).containsExactly(85, 98); // Thunderbolt, Quick Attack; Growl is a status move
    }

    @ParameterizedTest
    @CsvSource({
            "THUNDERPUNCH, 9",   // old games join the words
            "Thunder Punch, 9",
            "Donnerschlag, -1",  // German name in an English game: no match
            "Hydro Pumpe, 56",   // one extra letter: still Hydro Pump
            "POISON, -1",        // a type label is no move (Poison Jab, Poison Fang)
    })
    void matchesMoveNamesWithAndWithoutSpaces(String text, int expected) {
        MoveReader reader = new MoveReader(recognizing(List.of()), GEN8, Language.EN);

        assertThat(reader.match(List.of(text.split(" "))).orElse(-1)).isEqualTo(expected);
    }

    @Tag("ocr") // real OCR on screenshots: run by gradlew ocrTest, not test
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // file | area x, y, width, height relative to the screenshot | expected move ids
            "bdsp-en-moves-bite-acid-spray.png | 0.4, 0.2, 0.47, 0.3 | 44, 491",                // Toxic, Venom Drench: status
            // Teeter Dance: status. Trop Kick is highlighted by the cursor (dark text on yellow) and not read.
            "sv-en-moves-trop-kick-high-jump-kick-rapid-spin.png | 0.4, 0.32, 0.4, 0.33 | 136, 229",
    })
    void readsRealMoveLists(String file, String area, String expected) throws IOException {
        URL resource = MoveReaderTest.class.getResource("/screenshots/" + file);
        assumeTrue(resource != null, () -> file + " was not downloaded (see screenshots.tsv)");
        BufferedImage image = ImageIO.read(resource);
        double[] a = Arrays.stream(area.split(",")).mapToDouble(value -> Double.parseDouble(value.strip())).toArray();
        BufferedImage moveList = image.getSubimage((int) (a[0] * image.getWidth()), (int) (a[1] * image.getHeight()),
                (int) (a[2] * image.getWidth()), (int) (a[3] * image.getHeight()));
        Ruleset ruleset = RulesetLoader.load(file.startsWith("sv") ? "gen9" : "gen8");

        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            assertThat(new MoveReader(recognizer, ruleset, Language.EN).read(moveList))
                    .containsExactlyElementsOf(Arrays.stream(expected.split(",")).map(id -> Integer.parseInt(id.strip())).toList());
        }
    }

    /** The team editor reads the Pokémon's name next to its moves like a name box in battle (ui.TeamScan). */
    @Tag("ocr") // real OCR on screenshots: run by gradlew ocrTest, not test
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            // file | name area x, y, width, height relative to the screenshot | expected dex number, 0 = none
            "sv-en-moves-trop-kick-high-jump-kick-rapid-spin.png | 0.04, 0.15, 0.22, 0.07 | 763", // Tsareena
            "sv-en-moves-trop-kick-high-jump-kick-rapid-spin.png | 0.04, 0.27, 0.22, 0.07 | 823", // Corviknight
            "bdsp-en-moves-bite-acid-spray.png | 0.1, 0.07, 0.2, 0.07 | 0",                     // nickname "Lily"
    })
    void readsThePokemonNameNextToItsMoves(String file, String area, int expected) throws IOException {
        URL resource = MoveReaderTest.class.getResource("/screenshots/" + file);
        assumeTrue(resource != null, () -> file + " was not downloaded (see screenshots.tsv)");
        BufferedImage image = ImageIO.read(resource);
        double[] a = Arrays.stream(area.split(",")).mapToDouble(value -> Double.parseDouble(value.strip())).toArray();
        BufferedImage nameBox = image.getSubimage((int) (a[0] * image.getWidth()), (int) (a[1] * image.getHeight()),
                (int) (a[2] * image.getWidth()), (int) (a[3] * image.getHeight()));
        Ruleset ruleset = RulesetLoader.load(file.startsWith("sv") ? "gen9" : "gen8");

        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            List<Sighting> names = new BattleScanner(recognizer, new NameMatcher(ruleset, Language.EN), false).scan(nameBox);
            assertThat(names.stream().findFirst().map(sighting -> sighting.pokemon().speciesId()).orElse(0)).isEqualTo(expected);
        }
    }

    private static TextRecognizer recognizing(List<Word> words) {
        return new TextRecognizer() {
            @Override
            public List<Word> read(BufferedImage image) {
                return words;
            }

            @Override
            public void close() {
            }
        };
    }
}
