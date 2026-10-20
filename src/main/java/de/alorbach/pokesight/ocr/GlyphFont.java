package de.alorbach.pokesight.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The letter shapes of one game's font, learned on the user's own screen. Tesseract misreads some pixel fonts in a
 * consistent way (Black/White "Ramoth" as "kanoth" in every mode), although the letters are clean and separate after
 * {@link ImagePreprocessor}. So whenever Tesseract reads a word that is exactly a Pokémon name, the shapes of its letters
 * are stored under the letters of that name; later scans also read by matching shapes against them.
 *
 * <p>Shapes are compared on a small grid, together with their position on the line (so "o" and "O" or "," and "'"
 * differ): relative to the word's top line and baseline. Nothing of the games ships with the application: the shapes
 * come from the user's emulator window and stay on their computer.
 *
 * <p>Thread-safe: the scanner learns and reads on its thread, the session saves on closing.
 */
public final class GlyphFont {

    static final int GRID_WIDTH = 8;
    static final int GRID_HEIGHT = 12;
    /** Share of the grid cells two shapes of one letter may differ in (smoothing and binarization vary). */
    private static final double MAX_DIFFERENCE = 0.15;
    /** Largest difference of top, bottom and width of two shapes of one letter, in letter heights. */
    private static final double MAX_METRIC_DIFFERENCE = 0.2;
    /** Shapes differing in at most this many grid cells from a stored one add nothing new. */
    private static final int SAME_SHAPE = 3;
    /** Kept shapes per letter: the text/background limits and scale factors of the preprocessing all look different. */
    private static final int MAX_SHAPES_PER_LETTER = 8;
    /** A grid cell is set if this share of its pixels is text. */
    private static final double MIN_COVERAGE = 0.35;
    /** A gap of this many letter heights starts a new word (letters of pixel fonts are one or two pixels apart). */
    private static final double WORD_GAP = 0.35;
    /** Shapes of one letter overlap horizontally by at least this share of the narrower one (broken strokes). */
    private static final double MIN_LETTER_OVERLAP = 0.5;
    private static final int MIN_LETTER_HEIGHT = 4;
    private static final char UNKNOWN = '?';
    private static final System.Logger LOG = System.getLogger(GlyphFont.class.getName());

    /**
     * One learned shape.
     *
     * @param top    distance of its top from the word's top line, in letter heights
     * @param bottom distance of its bottom from the word's baseline, in letter heights (descenders are positive)
     * @param width  its width in letter heights
     * @param bits   {@value #GRID_WIDTH} x {@value #GRID_HEIGHT} grid of its bounding box, row by row
     */
    record Glyph(double top, double bottom, double width, long[] bits) {

        int difference(Glyph other) {
            return Long.bitCount(bits[0] ^ other.bits[0]) + Long.bitCount(bits[1] ^ other.bits[1]);
        }

        boolean similarMetrics(Glyph other) {
            return Math.abs(top - other.top) <= MAX_METRIC_DIFFERENCE && Math.abs(bottom - other.bottom) <= MAX_METRIC_DIFFERENCE
                    && Math.abs(width - other.width) <= MAX_METRIC_DIFFERENCE;
        }
    }

    /** A letter-sized box of text pixels on a line of a prepared image; bounds inclusive. */
    private record Cell(int left, int top, int right, int bottom) {
        int centerX() {
            return (left + right) / 2;
        }

        int centerY() {
            return (top + bottom) / 2;
        }

        Cell union(Cell other) {
            return new Cell(Math.min(left, other.left), Math.min(top, other.top), Math.max(right, other.right),
                    Math.max(bottom, other.bottom));
        }
    }

    private final Map<Character, List<Glyph>> glyphs = new LinkedHashMap<>();
    private boolean changed;

    /** {@code %LOCALAPPDATA%\PokeSight\glyphs\<game id>.txt}: learned data like the sprite cache, not a setting. */
    public static Path defaultFile(String gameId) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData != null
                ? Path.of(localAppData, "PokeSight", "glyphs")
                : Path.of(System.getProperty("user.home"), ".pokesight", "glyphs");
        return root.resolve(gameId + ".txt");
    }

    /** The shapes stored in the file; an empty font if there is none yet or it cannot be read (it is relearned). */
    public static GlyphFont load(Path file) {
        GlyphFont font = new GlyphFont();
        if (!Files.exists(file)) {
            return font;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                font.parse(line);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable letter shapes " + file, e);
            return new GlyphFont();
        }
        return font;
    }

    /** Writes the shapes atomically if anything was learned since loading. */
    public synchronized void save(Path file) throws IOException {
        if (!changed) {
            return;
        }
        StringBuilder text = new StringBuilder();
        glyphs.forEach((letter, shapes) -> shapes.forEach(glyph -> text.append(String.format(Locale.ROOT,
                "%04X %.3f %.3f %.3f %016X %016X%n", (int) letter, glyph.top(), glyph.bottom(), glyph.width(),
                glyph.bits()[0], glyph.bits()[1]))));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            changed = false;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void parse(String line) {
        if (line.isBlank()) {
            return;
        }
        String[] fields = line.trim().split(" ");
        if (fields.length != 6) {
            throw new IllegalArgumentException("Malformed letter shape: " + line);
        }
        Glyph glyph = new Glyph(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                new long[]{Long.parseUnsignedLong(fields[4], 16), Long.parseUnsignedLong(fields[5], 16)});
        glyphs.computeIfAbsent((char) Integer.parseInt(fields[0], 16), letter -> new ArrayList<>()).add(glyph);
    }

    public synchronized boolean isEmpty() {
        return glyphs.isEmpty();
    }

    /** Adds the shapes of another font (the screenshot tests learn from the other screenshots of a game). */
    public synchronized void addAll(GlyphFont other) {
        Map<Character, List<Glyph>> copy;
        synchronized (other) {
            copy = new HashMap<>();
            other.glyphs.forEach((letter, shapes) -> copy.put(letter, List.copyOf(shapes)));
        }
        copy.forEach((letter, shapes) -> shapes.forEach(glyph -> add(letter, glyph)));
    }

    /**
     * Learns the letters of a word that OCR read as {@code text}: only if the word has exactly one letter shape per
     * character (letters that touch or broke apart are not learned).
     *
     * @param prepared an image from {@link ImagePreprocessor#prepare}
     * @param word     where the word is in that image
     * @param text     what the word says, letter by letter in the game's spelling (no spaces)
     * @return whether the letters were learned
     */
    public synchronized boolean learn(BufferedImage prepared, Word word, String text) {
        List<Cell> cells = new ArrayList<>();
        boolean[] mask = mask(prepared);
        for (List<Cell> line : lines(mask, prepared.getWidth(), prepared.getHeight())) {
            for (Cell cell : line) {
                if (cell.centerX() >= word.left() && cell.centerX() <= word.right()
                        && cell.centerY() >= word.top() && cell.centerY() <= word.bottom()) {
                    cells.add(cell);
                }
            }
        }
        if (cells.size() != text.length() || text.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        List<Glyph> shapes = glyphs(mask, prepared.getWidth(), cells);
        if (shapes.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            add(text.charAt(i), shapes.get(i));
        }
        return true;
    }

    private void add(char letter, Glyph glyph) {
        List<Glyph> shapes = glyphs.computeIfAbsent(letter, key -> new ArrayList<>());
        if (shapes.stream().anyMatch(known -> known.difference(glyph) <= SAME_SHAPE && known.similarMetrics(glyph))) {
            return;
        }
        if (shapes.size() == MAX_SHAPES_PER_LETTER) {
            shapes.removeFirst(); // the oldest: the emulator window may have been resized since
        }
        shapes.add(glyph);
        changed = true;
    }

    /**
     * The words of a prepared image, read by their letter shapes. Unknown shapes become {@value #UNKNOWN}, which name
     * matching ignores (one missing letter).
     */
    public synchronized List<Word> read(BufferedImage prepared) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        boolean[] mask = mask(prepared);
        List<Word> words = new ArrayList<>();
        for (List<Cell> line : lines(mask, prepared.getWidth(), prepared.getHeight())) {
            for (List<Cell> word : words(line)) {
                List<Glyph> shapes = glyphs(mask, prepared.getWidth(), word);
                if (shapes.isEmpty()) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                shapes.forEach(shape -> text.append(classify(shape)));
                if (text.chars().anyMatch(c -> c != UNKNOWN)) {
                    Cell box = word.stream().reduce(Cell::union).orElseThrow();
                    words.add(new Word(text.toString(), box.left(), box.top(), box.right(), box.bottom()));
                }
            }
        }
        return words;
    }

    private char classify(Glyph shape) {
        char best = UNKNOWN;
        int bestDifference = (int) (GRID_WIDTH * GRID_HEIGHT * MAX_DIFFERENCE);
        for (Map.Entry<Character, List<Glyph>> entry : glyphs.entrySet()) {
            for (Glyph known : entry.getValue()) {
                int difference = known.difference(shape);
                if (difference <= bestDifference && known.similarMetrics(shape)
                        && (difference < bestDifference || best == UNKNOWN)) {
                    best = entry.getKey();
                    bestDifference = difference;
                }
            }
        }
        return best;
    }

    private static boolean[] mask(BufferedImage prepared) {
        int width = prepared.getWidth();
        int[] rgb = prepared.getRGB(0, 0, width, prepared.getHeight(), null, 0, width);
        boolean[] mask = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            mask[i] = (rgb[i] & 0xFF) < 128; // prepared images are black text on white
        }
        return mask;
    }

    /** Letter cells grouped into text lines, left to right: a shape belongs to a line if its centre lies within it. */
    private static List<List<Cell>> lines(boolean[] mask, int width, int height) {
        List<Cell> shapes = ImagePreprocessor.shapes(mask, width, height).stream()
                .map(shape -> new Cell(shape.left(), shape.top(), shape.right(), shape.bottom()))
                .sorted(Comparator.comparingInt(Cell::top))
                .toList();
        List<List<Cell>> lines = new ArrayList<>();
        List<Cell> line = null;
        Cell extent = null;
        for (Cell shape : shapes) {
            if (line == null || shape.centerY() > extent.bottom() || shape.centerY() < extent.top()) {
                line = new ArrayList<>();
                lines.add(line);
                extent = shape;
            }
            line.add(shape);
        }
        return lines.stream().map(GlyphFont::letters).toList();
    }

    /** Shapes of one line that overlap horizontally are one letter (strokes split by smoothing). */
    private static List<Cell> letters(List<Cell> line) {
        List<Cell> sorted = line.stream().sorted(Comparator.comparingInt(Cell::left)).toList();
        List<Cell> letters = new ArrayList<>();
        for (Cell shape : sorted) {
            if (!letters.isEmpty()) {
                Cell last = letters.getLast();
                int overlap = Math.min(last.right(), shape.right()) - Math.max(last.left(), shape.left()) + 1;
                int narrower = Math.min(last.right() - last.left(), shape.right() - shape.left()) + 1;
                if (overlap >= narrower * MIN_LETTER_OVERLAP) {
                    letters.set(letters.size() - 1, last.union(shape));
                    continue;
                }
            }
            letters.add(shape);
        }
        return letters;
    }

    /** Splits a line at gaps wider than {@value #WORD_GAP} letter heights. */
    private static List<List<Cell>> words(List<Cell> line) {
        int letterHeight = (int) line.stream().mapToInt(cell -> cell.bottom() - cell.top() + 1).average().orElse(0);
        List<List<Cell>> words = new ArrayList<>();
        Cell previous = null;
        for (Cell cell : line) {
            if (previous == null || cell.left() - previous.right() - 1 > letterHeight * WORD_GAP) {
                words.add(new ArrayList<>());
            }
            words.getLast().add(cell);
            previous = cell;
        }
        return words;
    }

    /**
     * The shapes of a word's letters, positioned relative to the word's top line (highest letter top) and baseline
     * (most common letter bottom); empty if the word is too small to compare.
     */
    private static List<Glyph> glyphs(boolean[] mask, int width, List<Cell> word) {
        int top = word.stream().mapToInt(Cell::top).min().orElse(0);
        int baseline = baseline(word);
        double height = baseline - top + 1;
        if (height < MIN_LETTER_HEIGHT) {
            return List.of();
        }
        List<Glyph> glyphs = new ArrayList<>();
        for (Cell cell : word) {
            glyphs.add(new Glyph((cell.top() - top) / height, (cell.bottom() - baseline) / height,
                    (cell.right() - cell.left() + 1) / height, grid(mask, width, cell)));
        }
        return glyphs;
    }

    private static int baseline(List<Cell> word) {
        Map<Integer, Integer> counts = new HashMap<>();
        word.forEach(cell -> counts.merge(cell.bottom(), 1, Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow()
                .getKey();
    }

    /** The cell scaled to the grid: a grid cell is set if enough of its pixels are text. */
    private static long[] grid(boolean[] mask, int width, Cell cell) {
        long[] bits = new long[2];
        int cellWidth = cell.right() - cell.left() + 1;
        int cellHeight = cell.bottom() - cell.top() + 1;
        for (int gy = 0; gy < GRID_HEIGHT; gy++) {
            int y0 = cell.top() + gy * cellHeight / GRID_HEIGHT;
            int y1 = Math.max(y0 + 1, cell.top() + (gy + 1) * cellHeight / GRID_HEIGHT);
            for (int gx = 0; gx < GRID_WIDTH; gx++) {
                int x0 = cell.left() + gx * cellWidth / GRID_WIDTH;
                int x1 = Math.max(x0 + 1, cell.left() + (gx + 1) * cellWidth / GRID_WIDTH);
                int text = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if (mask[y * width + x]) {
                            text++;
                        }
                    }
                }
                if (text >= (x1 - x0) * (y1 - y0) * MIN_COVERAGE) {
                    int bit = gy * GRID_WIDTH + gx;
                    bits[bit / 64] |= 1L << (bit % 64);
                }
            }
        }
        return bits;
    }
}
