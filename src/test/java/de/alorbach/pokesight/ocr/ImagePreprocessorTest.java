package de.alorbach.pokesight.ocr;

import de.alorbach.pokesight.ocr.ImagePreprocessor.TextColor;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static de.alorbach.pokesight.ocr.ImagePreprocessor.PADDING;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic cases; real screenshots are covered by {@code ScreenshotOcrTest}. */
class ImagePreprocessorTest {

    // Colours measured on real screenshots.
    private static final Color DP_TEXT = new Color(0x38, 0x38, 0x38);
    private static final Color DP_BOX = new Color(0xF8, 0xF8, 0xF8);
    private static final Color PT_TEXT = new Color(0xF8, 0xF8, 0xF8);
    private static final Color PT_SHADOW = new Color(0x28, 0x30, 0x28);
    private static final Color PT_BOX = new Color(0x68, 0x70, 0x58);
    /** Platinum Together: the name of the player's Pokémon whose turn it is, with a black outline. */
    private static final Color TURN_TEXT = new Color(0xFF, 0xD8, 0x58);
    private static final Color OUTLINE = new Color(0x08, 0x08, 0x0C);
    private static final Color SKY = new Color(0x60, 0xB0, 0xF8);
    private static final Color GRASS = new Color(0x58, 0xC8, 0x40);

    @Test
    void darkTextIsSeparatedFromTheBox() {
        BufferedImage box = letters(DP_BOX, DP_TEXT, DP_BOX, 8);

        boolean[] mask = ImagePreprocessor.textMask(box, TextColor.DARK);

        assertThat(mask[index(box, 3, 4)]).isTrue();
        assertThat(mask[index(box, 3, 30)]).isFalse();
    }

    @Test
    void lightTextIsSeparatedFromBoxAndShadow() {
        BufferedImage box = letters(PT_BOX, PT_TEXT, PT_SHADOW, 8);

        boolean[] mask = ImagePreprocessor.textMask(box, TextColor.LIGHT);

        assertThat(mask[index(box, 3, 4)]).isTrue();   // letter
        assertThat(mask[index(box, 5, 10)]).isFalse(); // shadow below the letter
        assertThat(mask[index(box, 3, 30)]).isFalse(); // box
    }

    @Test
    void yellowTextIsOnlyReadWhereTheGameHasIt() {
        BufferedImage box = letters(OUTLINE, TURN_TEXT, OUTLINE, 8);

        boolean[] yellow = ImagePreprocessor.textMask(box, TextColor.LIGHT_OR_YELLOW);
        boolean[] white = ImagePreprocessor.textMask(box, TextColor.LIGHT);

        assertThat(yellow[index(box, 3, 4)]).isTrue();
        assertThat(yellow[index(box, 3, 30)]).isFalse();
        assertThat(white[index(box, 3, 4)]).isFalse(); // yellow sand behind other games' boxes is no text
    }

    @Test
    void brightSkyAndGrassBesideTheBoxAreNotLightText() {
        for (Color scene : List.of(SKY, GRASS)) {
            BufferedImage box = letters(OUTLINE, PT_TEXT, OUTLINE, 8);
            Graphics2D graphics = box.createGraphics();
            graphics.setColor(scene); // bright enough for the limit: only its colour tells it from text
            graphics.fillRect(150, 0, 50, box.getHeight());
            graphics.dispose();

            boolean[] mask = ImagePreprocessor.textMask(box, TextColor.LIGHT_OR_YELLOW);

            assertThat(mask[index(box, 3, 4)]).as("letter beside %s", scene).isTrue();
            assertThat(mask[index(box, 170, 10)]).as("%s", scene).isFalse();
        }
    }

    @Test
    void measuresLetterHeightIgnoringBarsAndNoise() {
        BufferedImage box = letters(DP_BOX, DP_TEXT, DP_BOX, 8);
        Graphics2D graphics = box.createGraphics();
        graphics.setColor(DP_TEXT);
        graphics.fillRect(0, 34, box.getWidth(), 2); // HP bar outline
        graphics.fillRect(150, 2, 1, 1);             // a stray pixel
        graphics.dispose();

        boolean[] mask = ImagePreprocessor.textMask(box, TextColor.DARK);
        List<ImagePreprocessor.Shape> shapes = ImagePreprocessor.shapes(mask, box.getWidth(), box.getHeight());

        assertThat(ImagePreprocessor.letterHeight(shapes, box.getHeight())).hasValue(8);
    }

    @Test
    void keepsLettersDropsBarsAndEnlargesByWholeFactors() {
        BufferedImage box = letters(DP_BOX, DP_TEXT, DP_BOX, 8);
        Graphics2D graphics = box.createGraphics();
        graphics.setColor(DP_TEXT);
        graphics.fillRect(0, 34, box.getWidth(), 2); // HP bar outline
        graphics.dispose();

        BufferedImage prepared = ImagePreprocessor.prepare(box, TextColor.DARK, false).getFirst();

        assertThat(prepared.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
        assertThat(prepared.getWidth()).isEqualTo(box.getWidth() * 2 + 2 * PADDING); // 8 px letters -> 2x
        assertThat(prepared.getRGB(0, 0)).isEqualTo(Color.WHITE.getRGB());
        assertThat(prepared.getRGB(PADDING + 6, PADDING + 8)).isEqualTo(Color.BLACK.getRGB());   // letter
        assertThat(prepared.getRGB(PADDING + 320, PADDING + 68)).isEqualTo(Color.WHITE.getRGB()); // bar removed
    }

    @Test
    void joiningStrokesReconnectsLettersSplitBySmoothing() {
        BufferedImage box = letters(DP_BOX, DP_TEXT, DP_BOX, 8);
        Graphics2D graphics = box.createGraphics();
        graphics.setColor(DP_TEXT);
        graphics.fillRect(150, 2, 4, 2);  // short stroke ...
        graphics.fillRect(150, 5, 2, 5);  // ... and its vertical part, one pixel apart
        graphics.dispose();

        BufferedImage separate = ImagePreprocessor.prepare(box, TextColor.DARK, false).getFirst();
        BufferedImage joined = ImagePreprocessor.prepare(box, TextColor.DARK, true).getFirst();

        // The 2 px tall top stroke alone is too small to be a letter; joined with the vertical part it is kept.
        assertThat(separate.getRGB(PADDING + 152 * 2, PADDING + 2 * 2)).isEqualTo(Color.WHITE.getRGB());
        assertThat(joined.getRGB(PADDING + 152 * 2, PADDING + 2 * 2)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void largeCapturesAreShrunk() {
        BufferedImage box = letters(DP_BOX, DP_TEXT, DP_BOX, 60);

        BufferedImage prepared = ImagePreprocessor.prepare(box, TextColor.DARK, false).getFirst();

        assertThat(prepared.getWidth()).isLessThan(box.getWidth());
    }

    @Test
    void imageWithoutTextStaysBlank() {
        BufferedImage plain = new BufferedImage(50, 30, BufferedImage.TYPE_INT_RGB);

        BufferedImage prepared = ImagePreprocessor.prepare(plain, TextColor.LIGHT, true).getFirst();

        assertThat(prepared.getRGB(PADDING + 10, PADDING + 10)).isEqualTo(Color.WHITE.getRGB());
    }

    /** A box with a row of solid "letters" (given height) starting at x = 2, y = 2, each with a shadow one pixel down-right. */
    private static BufferedImage letters(Color box, Color text, Color shadow, int letterHeight) {
        int height = Math.max(40, letterHeight * 2 + 8);
        BufferedImage image = new BufferedImage(200, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(box);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        int letterWidth = Math.max(3, letterHeight * 3 / 8);
        for (int x = 2; x + letterWidth + 1 < 140; x += letterWidth + 3) {
            graphics.setColor(shadow);
            graphics.fillRect(x + 1, 3, letterWidth, letterHeight);
            graphics.setColor(text);
            graphics.fillRect(x, 2, letterWidth, letterHeight);
        }
        graphics.dispose();
        return image;
    }

    private static int index(BufferedImage image, int x, int y) {
        return y * image.getWidth() + x;
    }
}
