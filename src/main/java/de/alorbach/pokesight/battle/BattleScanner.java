package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.HpBars;
import de.alorbach.pokesight.ocr.ImagePreprocessor;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Reads the opponents' name boxes from a screenshot (a selected area or the whole screen). */
public final class BattleScanner {

    /** Share of upper-case letters a word needs to count as a name in games that write names in capitals. */
    private static final double MIN_CAPITALS = 0.75;
    /** Characters OCR reads for a capital I at the start of a mixed-case name ("lgglybuff"). */
    private static final String CAPITAL_I_LOOK_ALIKES = "l|1";
    /**
     * A floating name stands over its HP bar: its box centre lies within the bar's width (centred in Legends: Z-A, not
     * always in Scarlet/Violet). Text beside the bar is the 3D scene.
     */
    private static final double MAX_NAME_OFFSET = 0.5;
    /** Gap between two words, in letter heights, from which they belong to different name boxes. */
    private static final int MIN_BOX_GAP_LETTERS = 5;

    private final TextRecognizer recognizer;
    private final NameMatcher matcher;
    private final boolean capitalNames;
    private final boolean floatingNames;

    /** @param capitalNames the game writes names in capitals; words in mixed case are then not taken for names */
    public BattleScanner(TextRecognizer recognizer, NameMatcher matcher, boolean capitalNames) {
        this(recognizer, matcher, capitalNames, false);
    }

    /**
     * @param floatingNames the game shows each opponent's name above it in the 3D scene instead of in a fixed box;
     *                      only the labels above HP bars are read then (see {@link HpBars})
     */
    public BattleScanner(TextRecognizer recognizer, NameMatcher matcher, boolean capitalNames, boolean floatingNames) {
        this.recognizer = Objects.requireNonNull(recognizer);
        this.matcher = Objects.requireNonNull(matcher);
        this.capitalNames = capitalNames;
        this.floatingNames = floatingNames;
    }

    /**
     * One entry per name box whose text matches a Pokémon, top to bottom.
     * The text colour depends on the game, the right letter grouping on the emulator's smoothing and the letter height
     * on the font, so every combination is read and the reading with the most recognized names wins (see
     * {@link ImagePreprocessor}).
     */
    public List<Sighting> scan(BufferedImage image) {
        if (!floatingNames) {
            return scanBoxes(image);
        }
        List<Sighting> sightings = new ArrayList<>();
        List<Rectangle> read = new ArrayList<>();
        for (HpBars.Label label : HpBars.labelAreas(image)) {
            Rectangle area = label.area();
            if (read.stream().anyMatch(area::intersects)) {
                continue; // one bar found twice (e.g. its rounded ends as separate rows): one opponent
            }
            double barCentre = (label.bar().getCenterX() - area.x) / area.width;
            double maxOffset = MAX_NAME_OFFSET * label.bar().width / area.width;
            BiPredicate<List<Word>, BufferedImage> centred = (box, prepared) -> Math.abs(ImagePreprocessor.sourceShare(prepared,
                    (box.getFirst().left() + box.getLast().right()) / 2) - barCentre) <= maxOffset;
            // The name is the line right above the bar; anything higher up in the area is the scene behind the label.
            Optional<Sighting> sighting = scanBoxes(image.getSubimage(area.x, area.y, area.width, area.height), centred)
                    .stream()
                    .reduce((upper, lower) -> lower);
            if (sighting.isPresent()) {
                sightings.add(sighting.get());
                read.add(area);
            }
        }
        return sightings;
    }

    private List<Sighting> scanBoxes(BufferedImage image) {
        return scanBoxes(image, (box, prepared) -> true);
    }

    /** @param accept whether a box of words of a prepared image may hold a name */
    private List<Sighting> scanBoxes(BufferedImage image, BiPredicate<List<Word>, BufferedImage> accept) {
        List<List<Sighting>> readings = new ArrayList<>();
        for (ImagePreprocessor.TextColor textColor : ImagePreprocessor.TextColor.values()) {
            for (boolean joinStrokes : new boolean[]{false, true}) {
                for (BufferedImage prepared : ImagePreprocessor.prepare(image, textColor, joinStrokes)) {
                    List<Sighting> sightings = new ArrayList<>();
                    for (List<Word> line : lines(recognizer.read(prepared))) {
                        for (List<Word> box : boxes(line)) {
                            if (accept.test(box, prepared)) {
                                toSighting(box).ifPresent(sightings::add);
                            }
                        }
                    }
                    readings.add(sightings);
                }
            }
        }
        return best(readings);
    }

    /**
     * The reading to trust among those of all image variants: one with recognized names, then the one most variants
     * agree on (a misreading is usually specific to one variant), then the most recognized names and boxes. Measured
     * on the screenshot tests: 518 instead of 512 correctly read views with "most names" alone.
     */
    static List<Sighting> best(List<List<Sighting>> readings) {
        Comparator<List<Sighting>> order = Comparator.<List<Sighting>>comparingInt(reading -> recognizedCount(reading) > 0 ? 1 : 0)
                .thenComparingLong(reading -> readings.stream().filter(other -> samePokemon(other, reading)).count())
                .thenComparingLong(BattleScanner::recognizedCount)
                .thenComparingInt(List::size);
        List<Sighting> best = List.of();
        for (List<Sighting> reading : readings) {
            if (order.compare(reading, best) > 0) {
                best = reading;
            }
        }
        return best;
    }

    /** The same Pokémon in the same order; the raw text does not count. */
    static boolean samePokemon(List<Sighting> a, List<Sighting> b) {
        return a.stream().map(Sighting::pokemon).toList().equals(b.stream().map(Sighting::pokemon).toList());
    }

    private static long recognizedCount(List<Sighting> sightings) {
        return sightings.size();
    }

    /** Groups words into text lines: a word belongs to a line if its vertical center lies within that line. */
    static List<List<Word>> lines(List<Word> words) {
        List<Word> sorted = words.stream().sorted(Comparator.comparingInt(Word::top)).toList();
        List<List<Word>> lines = new ArrayList<>();
        int lineTop = 0;
        int lineBottom = -1;
        for (Word word : sorted) {
            if (lines.isEmpty() || word.centerY() > lineBottom || word.centerY() < lineTop) {
                lines.add(new ArrayList<>());
                lineTop = word.top();
                lineBottom = word.bottom();
            }
            lines.getLast().add(word);
        }
        lines.forEach(line -> line.sort(Comparator.comparingInt(Word::left)));
        return lines;
    }

    /**
     * Splits a text line into name boxes: since Gen 5 the boxes of double battles, triple battles and hordes stand
     * side by side. Within a box the widest gap (name to gender and level sign) is about four letters; between boxes
     * it is wider.
     */
    static List<List<Word>> boxes(List<Word> line) {
        int[] heights = line.stream().mapToInt(word -> word.bottom() - word.top()).sorted().toArray();
        int letterHeight = Math.max(1, heights[heights.length / 2]);
        List<List<Word>> boxes = new ArrayList<>();
        for (Word word : line) {
            if (boxes.isEmpty() || word.left() - boxes.getLast().getLast().right() > letterHeight * MIN_BOX_GAP_LETTERS) {
                boxes.add(new ArrayList<>());
            }
            boxes.getLast().add(word);
        }
        return boxes;
    }

    /**
     * The Pokémon in one name box, if any of its words (or adjacent words) matches a name. Levels, HP and gender signs
     * in the box are not read: they never match a name. Floating labels are read in a busy 3D scene, so there a name has
     * to start with a capital letter: lower-case noise then does not match short names ("ar Lok" is not Arbok). Pixel
     * fonts of the boxed games are read with the wrong letter case too often for that (measured: 30 instead of 37 views
     * of Black/White).
     */
    Optional<Sighting> toSighting(List<Word> line) {
        List<String> nameTokens = new ArrayList<>();
        for (Word word : line) {
            if (!capitalNames || isMostlyCapitals(word.text())) {
                nameTokens.add(word.text());
            }
        }
        String text = String.join(" ", line.stream().map(Word::text).toList());
        return candidates(nameTokens, floatingNames ? BattleScanner::startsWithCapital : token -> true).stream()
                .map(matcher::match)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(NameMatcher.Match::distance))
                .map(match -> new Sighting(text, match.pokemon()));
    }

    static boolean isMostlyCapitals(String word) {
        long letters = word.chars().filter(Character::isLetter).count();
        long capitals = word.chars().filter(Character::isUpperCase).count();
        return letters > 0 && capitals >= letters * MIN_CAPITALS;
    }

    static boolean startsWithCapital(String word) {
        return !word.isEmpty() && (Character.isUpperCase(word.charAt(0)) || CAPITAL_I_LOOK_ALIKES.indexOf(word.charAt(0)) >= 0);
    }

    /**
     * Single words, adjacent pairs ("MR." "MIME") and the whole line, in case OCR adds or splits words; each starting
     * with a word a name can start with.
     */
    private static List<String> candidates(List<String> tokens, Predicate<String> nameStart) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (nameStart.test(tokens.get(i))) {
                candidates.add(tokens.get(i));
                if (i + 1 < tokens.size()) {
                    candidates.add(tokens.get(i) + tokens.get(i + 1));
                }
            }
        }
        if (tokens.size() > 2 && nameStart.test(tokens.getFirst())) {
            candidates.add(String.join("", tokens));
        }
        return candidates;
    }
}
