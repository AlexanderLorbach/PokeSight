package de.alorbach.pokesight.ocr;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Turns a screenshot into what OCR reads best: only the letters, black on white, at a size Tesseract handles well.
 *
 * <p>Game fonts are pixel fonts with a drop shadow, and their colour depends on the game: Diamond/Pearl use dark
 * text on light boxes, Platinum white text on dark boxes. So the caller tries both {@link TextColor}s. Steps:
 * <ol>
 *   <li>Text pixels: on the text side of a limit between the text colour and the background. This drops the box
 *       and (for light text) the shadow.</li>
 *   <li>Letter height: the most common height of the connected shapes (all capitals of a pixel font share it).</li>
 *   <li>Only letter-high shapes are kept; box borders, HP bars and dark backgrounds confuse Tesseract's layout
 *       analysis. Optionally shapes are grouped on a widened mask to rejoin letters split by smoothing.</li>
 *   <li>Scaling to letters of about {@value #TARGET_LETTER_HEIGHT} px: whole-number pixel replication keeps the
 *       pixel shapes exact, clearly too large captures are shrunk with averaging.</li>
 * </ol>
 * The values were tuned on real Platinum and Diamond/Pearl screenshots at emulator sizes 1x-4x, sharp and smoothed
 * (see {@code ScreenshotOcrTest}).
 */
public final class ImagePreprocessor {

    public enum TextColor { DARK, LIGHT }

    static final int PADDING = 16;
    /** About twice the DS font size; clearly larger or smaller letters were read worse. */
    static final int TARGET_LETTER_HEIGHT = 18;
    /**
     * Where the text/background limit lies between the text colour (0) and the background (1). Swept 0.40-0.65 on the
     * screenshot tests: smaller values lose the blended strokes of smoothed output; 0.60 read the most cases.
     */
    private static final double TEXT_SIDE = 0.60;
    private static final int MIN_LETTER_HEIGHT = 4;
    private static final double MIN_SHRINK = 0.6;
    private static final byte BLACK = 0;
    private static final byte WHITE = (byte) 0xFF;

    /** A connected group of text pixels. */
    record Shape(int top, int bottom, int left, int right, int[] pixels) {
        int height() {
            return bottom - top + 1;
        }

        int width() {
            return right - left + 1;
        }
    }

    private ImagePreprocessor() {
    }

    /**
     * @param joinStrokes group pixels on a mask widened by one pixel. Smoothed (filtered) emulator output blurs letter
     *                    corners and splits letters into separate strokes, which this joins again; but it can also join
     *                    letters to a nearby box border. Callers try both. Only the original text pixels are output.
     */
    public static BufferedImage prepare(BufferedImage source, TextColor textColor, boolean joinStrokes) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean[] text = textMask(source, textColor);
        List<Shape> shapes = shapes(joinStrokes ? dilate(text, width, height) : text, width, height);
        OptionalInt letterHeight = letterHeight(shapes, height);
        if (letterHeight.isEmpty()) {
            return pad(blank(width, height)); // nothing that looks like text
        }
        int letter = letterHeight.getAsInt();
        boolean[] letters = new boolean[width * height];
        for (Shape shape : shapes) {
            if (isLetterSized(shape, letter)) {
                for (int index : shape.pixels()) {
                    letters[index] = text[index];
                }
            }
        }
        return pad(scale(toImage(letters, width, height), (double) TARGET_LETTER_HEIGHT / letter));
    }

    /** Every pixel next to a text pixel (3 x 3 neighbourhood) becomes text too. */
    static boolean[] dilate(boolean[] mask, int width, int height) {
        boolean[] result = new boolean[mask.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x]) {
                    for (int ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1); ny++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                            result[ny * width + nx] = true;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Pixels on the text side of a limit between the text colour (darkest or brightest percentile, which ignores
     * a few stray pixels) and the background (median). The limit sits closer to the text than the drop shadow is,
     * but wide enough to keep the blended edges of smoothed (filtered) emulator output.
     */
    static boolean[] textMask(BufferedImage source, TextColor textColor) {
        int[] luminance = luminance(source);
        int[] sorted = luminance.clone();
        Arrays.sort(sorted);
        int onePercent = sorted.length / 100;
        int median = sorted[sorted.length / 2];
        int limit = textColor == TextColor.DARK
                ? sorted[onePercent] + (int) ((median - sorted[onePercent]) * TEXT_SIDE)
                : sorted[sorted.length - 1 - onePercent] - (int) ((sorted[sorted.length - 1 - onePercent] - median) * TEXT_SIDE);
        boolean[] text = new boolean[luminance.length];
        for (int i = 0; i < luminance.length; i++) {
            text[i] = textColor == TextColor.DARK ? luminance[i] <= limit : luminance[i] >= limit;
        }
        return text;
    }

    /** Connected shapes of the mask; diagonal neighbours count, because pixel fonts draw curves as diagonal steps. */
    static List<Shape> shapes(boolean[] mask, int width, int height) {
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        List<Shape> shapes = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int top = Integer.MAX_VALUE, bottom = -1, left = Integer.MAX_VALUE, right = -1;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                left = Math.min(left, x);
                right = Math.max(right, x);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < width && ny < height) {
                            tail = visit(mask, seen, queue, tail, ny * width + nx);
                        }
                    }
                }
            }
            shapes.add(new Shape(top, bottom, left, right, Arrays.copyOf(queue, tail)));
        }
        return shapes;
    }

    private static int visit(boolean[] mask, boolean[] seen, int[] queue, int tail, int index) {
        if (mask[index] && !seen[index]) {
            seen[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    /**
     * The most common height among shapes that could be letters or words: not tiny (noise), not taller than half
     * the image (box borders), not a thin vertical line. On a tie the taller height wins (capitals).
     */
    static OptionalInt letterHeight(List<Shape> shapes, int imageHeight) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Shape shape : shapes) {
            int height = shape.height();
            if (height >= MIN_LETTER_HEIGHT && height <= imageHeight / 2 && shape.width() * 4 >= height && shape.pixels().length >= height) {
                counts.merge(height, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> OptionalInt.of(entry.getKey()))
                .orElse(OptionalInt.empty());
    }

    /**
     * Letters, words, digits and the "Lv" sign are about one letter high; small parts (dots) and shapes of a clearly
     * different height (thin HP bars, box borders, backgrounds) are dropped.
     */
    private static boolean isLetterSized(Shape shape, int letterHeight) {
        return shape.height() * 2 >= letterHeight && shape.height() * 2 <= letterHeight * 3;
    }

    /**
     * Whole-number pixel replication when enlarging keeps pixel shapes exact; area averaging when shrinking.
     * Letters up to about 1.5x the target are left alone: shrinking them thins smoothed strokes until they break.
     */
    private static BufferedImage scale(BufferedImage image, double scale) {
        if (scale >= MIN_SHRINK) {
            int factor = Math.max(1, (int) Math.round(scale));
            if (factor == 1) {
                return image;
            }
            BufferedImage result = new BufferedImage(image.getWidth() * factor, image.getHeight() * factor, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = result.createGraphics();
            graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null); // default: nearest neighbour
            graphics.dispose();
            return result;
        }
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        graphics.dispose();
        byte[] pixels = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (pixels[i] & 0xFF) < 128 ? BLACK : WHITE;
        }
        return result;
    }

    private static BufferedImage toImage(boolean[] text, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < text.length; i++) {
            pixels[i] = text[i] ? BLACK : WHITE;
        }
        return image;
    }

    private static BufferedImage blank(int width, int height) {
        return toImage(new boolean[width * height], width, height);
    }

    private static BufferedImage pad(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth() + 2 * PADDING, image.getHeight() + 2 * PADDING, BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, WHITE);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(image, PADDING, PADDING, null);
        graphics.dispose();
        return result;
    }

    private static int[] luminance(BufferedImage image) {
        int width = image.getWidth();
        int[] rgb = image.getRGB(0, 0, width, image.getHeight(), null, 0, width);
        int[] luminance = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int pixel = rgb[i];
            luminance[i] = (((pixel >> 16) & 0xFF) * 299 + ((pixel >> 8) & 0xFF) * 587 + (pixel & 0xFF) * 114) / 1000;
        }
        return luminance;
    }
}
