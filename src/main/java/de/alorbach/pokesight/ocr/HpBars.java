package de.alorbach.pokesight.ocr;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the labels of the 3D games that show an opponent's name floating above it (Legends: Arceus, Scarlet/Violet,
 * Legends: Z-A). A 3D scene is far too busy to read as a whole, but every label has a solid HP bar (green, yellow or
 * red) right below the name, which is easy to find: a flat rectangle of one saturated colour. At low HP only a short
 * piece is coloured; the dark empty part of the bar right of it counts for the bar's size.
 */
public final class HpBars {

    /** Saturated HP bar colours: minimum difference between the largest and smallest colour channel. */
    private static final int MIN_CHROMA = 90;
    private static final int MIN_HEIGHT = 2;
    private static final int MAX_HEIGHT = 24;
    private static final int MIN_WIDTH = 24;
    /** Narrowest coloured piece of a bar that is almost empty. */
    private static final int MIN_FILL_WIDTH = 4;
    /** HP bars are long and flat; grass, effects and sky patches are not. */
    private static final int MIN_ASPECT = 6;
    /** A bar with its empty part is at most this many times as wide as high; limits the empty part in dark scenes. */
    private static final int MAX_ASPECT = 20;
    /** The empty part of a bar: dark and even, at most this bright and this far from the pixel left of it. */
    private static final int MAX_EMPTY_BRIGHTNESS = 120;
    private static final int EMPTY_STEP = 40;
    /** Largest difference of an empty-part pixel from the row's mean colour, per channel. */
    private static final int EMPTY_TOLERANCE = 32;
    /** Antialiased pixels between the coloured and the empty part. */
    private static final int FILL_EDGE = 2;
    /** Rounded or slanted bar ends: rows of one bar may start and end this many pixels apart. */
    private static final int RAGGED_ENDS = 5;
    /** The rounded first and last rows of a bar may be shorter on one side; they still overlap most of the bar. */
    private static final double MIN_ROW_OVERLAP = 0.75;
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
                    if (continues(bar, run)) {
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
                .filter(fill -> fill.height >= MIN_HEIGHT && fill.height <= MAX_HEIGHT)
                .map(fill -> withEmptyPart(rgb, width, fill))
                .filter(bar -> bar.width >= MIN_WIDTH && bar.width >= bar.height * MIN_ASPECT
                        && inTrack(rgb, width, height, bar))
                .toList();
    }

    /** A run in the next row belongs to the bar if both ends line up, or one end does and it covers most of the bar. */
    private static boolean continues(Rectangle bar, int[] run) {
        boolean sameStart = Math.abs(bar.x - run[0]) <= RAGGED_ENDS;
        boolean sameEnd = Math.abs(bar.x + bar.width - run[1]) <= RAGGED_ENDS;
        int overlap = Math.min(bar.x + bar.width, run[1]) - Math.max(bar.x, run[0]);
        return sameStart && sameEnd
                || (sameStart || sameEnd) && overlap >= MIN_ROW_OVERLAP * Math.max(bar.width, run[1] - run[0]);
    }

    /**
     * The coloured part of a bar and the dark, even empty part right of it, if most rows of the fill go on into it (the
     * lighter edge rows may not). A bright frame or background ends the empty part.
     */
    private static Rectangle withEmptyPart(int[] rgb, int width, Rectangle fill) {
        int start = fill.x + fill.width + FILL_EDGE;
        int limit = Math.min(width, fill.x + fill.height * MAX_ASPECT);
        List<Integer> ends = new ArrayList<>();
        for (int y = fill.y; y < fill.y + fill.height; y++) {
            int rowEnd = start;
            while (rowEnd < limit && isEmptyPart(rgb[y * width + rowEnd], rgb[y * width + rowEnd - 1], rowEnd == start)) {
                rowEnd++;
            }
            if (rowEnd - start >= MIN_FILL_WIDTH) {
                ends.add(rowEnd);
            }
        }
        if (ends.size() < fill.height * MIN_TRACK_SHARE) {
            return fill; // a full bar, or something else right of the fill
        }
        ends.sort(null);
        int end = ends.get(ends.size() / 2);
        return isEven(rgb, width, fill, start, end) ? new Rectangle(fill.x, fill.y, end - fill.x, fill.height) : fill;
    }

    /** An empty bar part has one colour per row, unlike sparkles or leaves on a dark background. */
    private static boolean isEven(int[] rgb, int width, Rectangle fill, int start, int end) {
        int even = 0;
        for (int y = fill.y; y < fill.y + fill.height; y++) {
            long r = 0;
            long g = 0;
            long b = 0;
            for (int x = start; x < end; x++) {
                int pixel = rgb[y * width + x];
                r += (pixel >> 16) & 0xFF;
                g += (pixel >> 8) & 0xFF;
                b += pixel & 0xFF;
            }
            int n = end - start;
            int mean = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            for (int x = start; x < end; x++) {
                if (differsBy(rgb[y * width + x], mean) <= EMPTY_TOLERANCE) {
                    even++;
                }
            }
        }
        return even >= (end - start) * fill.height * MIN_TRACK_SHARE;
    }

    private static boolean isEmptyPart(int pixel, int left, boolean first) {
        return !isBarColour(pixel) && brightness(pixel) <= MAX_EMPTY_BRIGHTNESS
                && (first || Math.abs(brightness(pixel) - brightness(left)) <= EMPTY_STEP);
    }

    /**
     * HP bars run in a frame or track: the rows right above and below are mostly not bar-coloured, unlike a strip of
     * grass. Translucent frames take on the colour behind them, so only bar colours count against it.
     */
    private static boolean inTrack(int[] rgb, int width, int height, Rectangle bar) {
        for (int y : new int[]{bar.y - TRACK_DISTANCE, bar.y + bar.height - 1 + TRACK_DISTANCE}) {
            if (y < 0 || y >= height) {
                return false;
            }
            int track = 0;
            for (int x = bar.x; x < bar.x + bar.width; x++) {
                if (!isBarColour(rgb[y * width + x])) {
                    track++;
                }
            }
            if (track < bar.width * MIN_TRACK_SHARE) {
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
                if (x - start >= MIN_FILL_WIDTH) {
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

    private static int brightness(int pixel) {
        return Math.max((pixel >> 16) & 0xFF, Math.max((pixel >> 8) & 0xFF, pixel & 0xFF));
    }

    private static int chroma(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static boolean similar(int a, int b) {
        return differsBy(a, b) < 60;
    }

    /** Largest difference of the two colours in one channel. */
    private static int differsBy(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
                Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
    }
}
