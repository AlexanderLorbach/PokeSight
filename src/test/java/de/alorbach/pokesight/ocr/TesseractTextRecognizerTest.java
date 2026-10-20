package de.alorbach.pokesight.ocr;

import de.alorbach.pokesight.battle.BattleScanner;
import de.alorbach.pokesight.battle.NameMatcher;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.battle.Sighting;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Runs the real Tesseract engine with the bundled models on rendered name boxes. */
class TesseractTextRecognizerTest {

    private static final Ruleset GEN4 = RulesetLoader.load("gen4");

    @Test
    void closingTwiceIsHarmlessAndReadingAfterwardsFails() throws Exception {
        TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN);
        recognizer.close();
        recognizer.close(); // freeing the native engine twice would crash the JVM

        assertThatThrownBy(() -> recognizer.read(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAnEnglishNameBox() throws Exception {
        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            BattleScanner scanner = new BattleScanner(recognizer, new NameMatcher(GEN4, Language.EN), true);

            List<Sighting> sightings = scanner.scan(nameBoxes("GENGAR Lv32"));

            assertThat(sightings).extracting(Sighting::pokemon, Sighting::level)
                    .containsExactly(tuple(Optional.of(PokemonId.of(94)), OptionalInt.of(32)));
        }
    }

    @Test
    void readsBothGermanNamesOfADoubleBattle() throws Exception {
        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.DE)) {
            BattleScanner scanner = new BattleScanner(recognizer, new NameMatcher(GEN4, Language.DE), true);

            List<Sighting> sightings = scanner.scan(nameBoxes("GARADOS Lv30", "BURMADAME Lv25"));

            assertThat(sightings).extracting(Sighting::pokemon)
                    .containsExactly(Optional.of(PokemonId.of(130)), Optional.of(PokemonId.of(413)));
        }
    }

    @Test
    void findsNothingOnAnEmptyScreen() throws Exception {
        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            assertThat(recognizer.read(ImagePreprocessor.prepare(nameBoxes(), ImagePreprocessor.TextColor.DARK, false).getFirst())).isEmpty();
        }
    }

    /** Dark text on light boxes, roughly like a DS battle screen captured at 2x. */
    private static BufferedImage nameBoxes(String... lines) {
        BufferedImage image = new BufferedImage(512, 60 + 70 * lines.length, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(90, 150, 90)); // grass-like battle background
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        for (int i = 0; i < lines.length; i++) {
            int top = 30 + i * 70;
            graphics.setColor(new Color(245, 245, 235));
            graphics.fillRoundRect(10, top, 300, 44, 12, 12);
            graphics.setColor(new Color(40, 40, 40));
            graphics.drawString(lines[i], 22, top + 30);
        }
        graphics.dispose();
        return image;
    }
}
