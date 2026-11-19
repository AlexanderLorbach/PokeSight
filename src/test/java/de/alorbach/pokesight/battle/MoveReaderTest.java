package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.game.GameCatalog;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.game.GameRulesets;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
            "Dreaming Kiss, 577",      // Draining Kiss in Platinum Together's pixel font: 2.5 letters off, nothing else close
            "Disarmng Yoce, 574",      // Disarming Voice
            "Treermtne Victor, -1",    // noise: far from every move
            "Sacrod Swond, 533",       // Sacred Sword, not Secret Sword: a long name close to another stays strict
    })
    void matchesMoveNamesWithAndWithoutSpaces(String text, int expected) {
        MoveReader reader = new MoveReader(recognizing(List.of()), GEN8, Language.EN);

        assertThat(reader.match(List.of(text.split(" "))).orElse(-1)).isEqualTo(expected);
    }

    @Test
    void typeLabelsBelowTheNamesAreNoMoves() {
        // Platinum Together: name above, type label and PP below. Hypnosis is a status move.
        List<Word> words = List.of(
                new Word("Hypnosis", 10, 10, 120, 30), new Word("Psybeam", 400, 10, 500, 30),
                new Word("PSYCHIC", 10, 50, 100, 70), new Word("@", 110, 50, 130, 70), new Word("PP", 200, 50, 220, 70),
                new Word("20/20", 226, 50, 280, 70),
                new Word("PSYCHIC", 400, 50, 490, 70), new Word("PP", 590, 50, 610, 70), new Word("20/20", 616, 50, 670, 70));

        assertThat(read(words, GEN8)).containsExactly(60); // Psybeam, not Psychic from its label
    }

    @Test
    void aStatusIconReadAsAWordDoesNotHideTheLabel() {
        // Platinum Together at a smaller capture: Hypnosis's grey status icon is read as "CaN)".
        List<Word> words = List.of(
                new Word("Hypnosis", 10, 10, 120, 30),
                new Word("PSYCHIC", 10, 50, 100, 70), new Word("CaN)", 110, 50, 150, 70), new Word("PP", 160, 50, 180, 70),
                new Word("20/20", 186, 50, 240, 70));

        assertThat(read(words, GEN8)).isEmpty();
    }

    @Test
    void aMoveNamedLikeItsTypeIsReadBesideItsLabel() {
        // Sword/Shield: name, label and PP counter in one row.
        List<Word> words = List.of(
                new Word("Psychic", 10, 10, 100, 30), new Word("PSYCHIC", 300, 10, 390, 30), new Word("10/10", 400, 10, 450, 30));

        assertThat(read(words, GEN8)).containsExactly(94);
    }

    @Test
    void labelsAfterTheCounterAndAfterTypeAreNoMoves() {
        List<Word> words = List.of(
                new Word("Peck", 300, 10, 350, 30),
                new Word("PP", 300, 50, 320, 70), new Word("35/35", 326, 50, 380, 70), new Word("PSYCHIC", 390, 50, 470, 70),
                new Word("TYPE/", 300, 90, 350, 110), new Word("PSYCHIC", 356, 90, 440, 110));

        assertThat(read(words, GEN8)).containsExactly(64); // Peck (ORAS right column; Gen 3 "TYPE/PSYCHIC")
    }

    @Test
    void readsTwoMovesInOneBox() {
        // HeartGold/SoulSilver: the gap between the columns is narrower than between name boxes.
        List<Word> words = List.of(new Word("Flamethrower", 10, 10, 120, 30), new Word("Earthquake", 140, 10, 240, 30));

        assertThat(read(words, GEN8)).containsExactly(53, 89);
    }

    @Tag("ocr") // real OCR on screenshots: run by gradlew ocrTest, not test
    @ParameterizedTest(name = "{0}")
    @MethodSource("moveLists")
    void readsRealMoveLists(String file, String gameId, Rectangle area, List<String> expected) throws IOException {
        URL resource = MoveReaderTest.class.getResource("/screenshots/" + file);
        assumeTrue(resource != null, () -> file + " was not downloaded (see screenshots.tsv)");
        BufferedImage moveList = ImageIO.read(resource).getSubimage(area.x, area.y, area.width, area.height);
        GameDefinition game = GameCatalog.load().stream().filter(candidate -> candidate.id().equals(gameId)).findFirst().orElseThrow();
        Ruleset ruleset = RulesetLoader.load(game.ruleset());

        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            assertThat(new MoveReader(recognizer, ruleset, Language.EN).read(moveList))
                    .map(id -> ruleset.findMove(id).orElseThrow().name(Language.EN))
                    .containsExactlyElementsOf(expected);
        }
    }

    /**
     * Move lists of games without wiki screenshots, from the player's own screenshots: kept out of Git
     * (© Nintendo; local-screenshots/ is ignored). Put the file there to run its case; the whole image is the move list.
     */
    @Tag("ocr") // real OCR on screenshots: run by gradlew ocrTest, not test
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // file | game id | game option switched on, or empty | expected damaging moves
            "platinum-together-en-moves-draining-kiss-psybeam.png | platinum-together | modern-pokemon | Draining Kiss+Disarming Voice+Psybeam",
    })
    void readsLocalMoveLists(String file, String gameId, String option, String expected) throws IOException {
        Path path = Path.of("local-screenshots", file);
        assumeTrue(Files.exists(path), () -> path + " is missing (a local screenshot, not in Git)");
        GameDefinition game = GameCatalog.load().stream().filter(candidate -> candidate.id().equals(gameId)).findFirst().orElseThrow();
        Ruleset ruleset = GameRulesets.load(game, option == null ? Map.of() : Map.of(option, true));

        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            assertThat(new MoveReader(recognizer, ruleset, Language.EN).read(ImageIO.read(path.toFile())))
                    .map(id -> ruleset.findMove(id).orElseThrow().name(Language.EN))
                    .containsExactly(expected.split("\\+"));
        }
    }

    /** The move lists of {@code screenshots.tsv}: file, game id, move list area, expected damaging moves. */
    static Stream<Arguments> moveLists() {
        return GameScreenshotOcrTest.rows()
                .filter(fields -> fields[5].startsWith(MOVES))
                .map(fields -> {
                    String moves = fields[5].substring(MOVES.length());
                    int[] a = Arrays.stream(fields[6].split(",")).mapToInt(Integer::parseInt).toArray();
                    return Arguments.of(fields[0], fields[3], new Rectangle(a[0], a[1], a[2], a[3]),
                            moves.isEmpty() ? List.of() : List.of(moves.split("\\+")));
                });
    }

    private static List<Integer> read(List<Word> words, Ruleset ruleset) {
        return new MoveReader(recognizing(words), ruleset, Language.EN).read(new BufferedImage(700, 120, BufferedImage.TYPE_INT_RGB));
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
