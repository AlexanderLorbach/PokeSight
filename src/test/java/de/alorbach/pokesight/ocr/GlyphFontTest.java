package de.alorbach.pokesight.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GlyphFontTest {

    private static final Font FONT = new Font(Font.MONOSPACED, Font.BOLD, 28);
    private static final int LETTER_GAP = 3;

    /** Black text on white like a prepared image, and where the word is. */
    private record Text(BufferedImage image, Word word) {
    }

    private static Text text(String value) {
        BufferedImage image = new BufferedImage(400, 80, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(FONT);
        FontMetrics metrics = graphics.getFontMetrics();
        int baseline = 50;
        int x = 20;
        for (char letter : value.toCharArray()) {
            graphics.drawString(String.valueOf(letter), x, baseline);
            x += metrics.charWidth(letter) + LETTER_GAP; // pixel fonts keep letters apart
        }
        graphics.dispose();
        return new Text(image, new Word(value, 20, baseline - metrics.getAscent(), x, baseline + metrics.getDescent()));
    }

    @Test
    void readsLettersItHasLearned() {
        GlyphFont font = new GlyphFont();
        Text learned = text("GENGAR");

        assertThat(font.learn(learned.image(), learned.word(), "GENGAR")).isTrue();

        assertThat(font.read(text("RANGE").image())).extracting(Word::text).containsExactly("RANGE");
    }

    @Test
    void marksUnknownLetters() {
        GlyphFont font = new GlyphFont();
        Text learned = text("GENGAR");
        font.learn(learned.image(), learned.word(), "GENGAR");

        assertThat(font.read(text("BEAR").image())).extracting(Word::text).containsExactly("?EAR");
    }

    @Test
    void doesNotLearnWhenLettersAndShapesDoNotPairUp() {
        GlyphFont font = new GlyphFont();
        Text learned = text("GENGAR");

        assertThat(font.learn(learned.image(), learned.word(), "GENGARX")).isFalse();
        assertThat(font.isEmpty()).isTrue();
    }

    @Test
    void readsNothingBeforeLearning() {
        assertThat(new GlyphFont().read(text("GENGAR").image())).isEmpty();
    }

    @Test
    void keepsWhatItLearnedAcrossSessions(@TempDir Path directory) throws IOException {
        GlyphFont font = new GlyphFont();
        Text learned = text("GENGAR");
        font.learn(learned.image(), learned.word(), "GENGAR");
        Path file = directory.resolve("glyphs").resolve("platinum.txt");

        font.save(file);

        assertThat(GlyphFont.load(file).read(text("RANGE").image())).extracting(Word::text).containsExactly("RANGE");
        assertThat(directory.resolve("glyphs")).isDirectoryContaining(path -> path.equals(file))
                .isDirectoryNotContaining("glob:**.tmp");
    }

    @Test
    void ignoresAnUnreadableFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("broken.txt");
        Files.writeString(file, "not a letter shape");

        assertThat(GlyphFont.load(file).isEmpty()).isTrue();
    }

    @Test
    void addsTheShapesOfAnotherFont() {
        GlyphFont other = new GlyphFont();
        Text learned = text("GENGAR");
        other.learn(learned.image(), learned.word(), "GENGAR");
        GlyphFont font = new GlyphFont();

        font.addAll(other);

        assertThat(font.read(text("RANGE").image())).extracting(Word::text).containsExactly("RANGE");
    }
}
