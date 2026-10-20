package de.alorbach.pokesight.ocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the labels of the 3D games that show an opponent's name floating above it (Legends: Arceus, Scarlet/Violet,
 * Legends: Z-A). A 3D scene is far too busy to read as a whole, but every label has a solid HP bar (green, yellow or
 * red) right below the name, which is easy to find: a flat rectangle of one saturated colour.
 */
public final class HpBars {

    /** Saturated HP bar colours: minimum difference between the largest and smallest colour channel. */
    private static final int MIN_CHROMA = 90;
    private static final int MIN_HEIGHT = 2;
    private static final int MAX_HEIGHT = 24;
    private static final int MIN_WIDTH = 24;
    /** HP bars are long and flat; grass, effects and sky patches are not. */
    private static final int MIN_ASPECT = 6;
    /** Rounded or slanted bar ends: rows of one bar may start and end this many pixels apart. */
    private static final int RAGGED_ENDS = 5;
    private static final int MAX_TRACK_CHROMA = 60;
    /** Rows checked above and below a bar: two pixels away, past the rounded ends. */
    private static final int TRACK_DISTANCE = 2;
    private static final double MIN_TRACK_SHARE = 0.6;
    /** Bars this low on the screen belong to your own Pokémon's box. */
    private static final double OWN_BOX_FROM = 0.8;
    /** Height of the label area above a bar, relative to the bar's width: level line and name line. */
    private static final double LABEL_HEIGHT = 0.42;
    private static final double LABEL_MARGIN = 0.25;

    private HpBars() {
    }

    /** The areas above the opponents' HP bars, top to bottom; each holds one opponent's level and name. */
    public static List<Rectangle> labelAreas(BufferedImage image) {
        List<Rectangle> areas = new ArrayList<>();
        for (Rectangle bar : bars(image)) {
            if (bar.y >= image.getHeight() * OWN_BOX_FROM) {
                continue;
            }
            int margin = (int) (bar.width * LABEL_MARGIN);
            int height = (int) (bar.width * LABEL_HEIGHT);
            Rectangle area = new Rectangle(bar.x - margin, bar.y - height, bar.width + 2 * margin, height)
                    .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
            if (!area.isEmpty()) {
                areas.add(area);
            }
        }
        areas.sort(Comparator.comparingInt((Rectangle area) -> area.y).thenComparingInt(area -> area.x));
        return areas;
    }

    /** Flat rectangles of saturated colour: rows of bar-coloured runs stacked with nearly the same extent. */
    static List<Rectangle> bars(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        List<Rectangle> open = new ArrayList<>();
        List<Rectangle> done = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            List<int[]> runs = runs(rgb, width, y);
            List<Rectangle> next = new ArrayList<>();
            for (int[] run : runs) {
                Rectangle continued = null;
                for (Rectangle bar : open) {
                    if (Math.abs(bar.x - run[0]) <= RAGGED_ENDS && Math.abs(bar.x + bar.width - run[1]) <= RAGGED_ENDS) {
                        continued = bar;
                        break;
                    }
                }
                if (continued != null) {
                    open.remove(continued);
                    continued.add(new Rectangle(run[0], y, run[1] - run[0], 1));
                    next.add(continued);
                } else {
                    next.add(new Rectangle(run[0], y, run[1] - run[0], 1));
                }
            }
            done.addAll(open);
            open = next;
        }
        done.addAll(open);
        return done.stream()
                .filter(bar -> bar.height >= MIN_HEIGHT && bar.height <= MAX_HEIGHT && bar.width >= MIN_WIDTH
                        && bar.width >= bar.height * MIN_ASPECT && inTrack(rgb, width, height, bar))
                .toList();
    }

    /** HP bars run in a grey or dark track: the rows right above and below are mostly uncoloured, unlike sky or grass. */
    private static boolean inTrack(int[] rgb, int width, int height, Rectangle bar) {
        for (int y : new int[]{bar.y - TRACK_DISTANCE, bar.y + bar.height - 1 + TRACK_DISTANCE}) {
            if (y < 0 || y >= height) {
                return false;
            }
            int neutral = 0;
            for (int x = bar.x; x < bar.x + bar.width; x++) {
                if (chroma(rgb[y * width + x]) < MAX_TRACK_CHROMA) {
                    neutral++;
                }
            }
            if (neutral < bar.width * MIN_TRACK_SHARE) {
                return false;
            }
        }
        return true;
    }

    /** Runs of pixels in one row that have a saturated colour of similar hue: {start, end exclusive}. */
    private static List<int[]> runs(int[] rgb, int width, int y) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        int startColor = 0;
        for (int x = 0; x <= width; x++) {
            int pixel = x < width ? rgb[y * width + x] : 0;
            boolean bar = x < width && isBarColour(pixel) && (start < 0 || similar(pixel, startColor));
            startColor = bar ? pixel : startColor; // compared with the previous pixel: bars have a gradient
            if (bar && start < 0) {
                start = x;
                startColor = pixel;
            } else if (!bar && start >= 0) {
                if (x - start >= MIN_WIDTH) {
                    runs.add(new int[]{start, x});
                }
                start = x < width && isBarColour(pixel) ? x : -1;
                startColor = pixel;
            }
        }
        return runs;
    }

    /** Green, yellow, orange or red with high saturation: red or green is the strongest channel, not blue. */
    private static boolean isBarColour(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return chroma(pixel) >= MIN_CHROMA && b < Math.max(r, g);
    }

    private static int chroma(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static boolean similar(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) < 60
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) < 60
                && Math.abs((a & 0xFF) - (b & 0xFF)) < 60;
    }
}
