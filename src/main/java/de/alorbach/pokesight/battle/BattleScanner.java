package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.ImagePreprocessor;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the opponents' name boxes from a screenshot (a selected area or the whole screen). */
public final class BattleScanner {

    /** "32", "Lv32", "Lv.32", "L32"; OCR often reads the small "v" as "u" or "y". */
    private static final Pattern LEVEL = Pattern.compile("(?i)(L[VUY]?\\.?)?(\\d{1,3})");
    /** A separate "Lv" sign in front of the number. */
    private static final Pattern LEVEL_SIGN = Pattern.compile("(?i)L[VUY]?\\.?");
    private static final int MAX_LEVEL = 100;
    /** Share of upper-case letters a word needs to count as a name in games that write names in capitals. */
    private static final double MIN_CAPITALS = 0.75;

    private final TextRecognizer recognizer;
    private final NameMatcher matcher;
    private final boolean capitalNames;

    /** @param capitalNames the game writes names in capitals; words in mixed case are then not taken for names */
    public BattleScanner(TextRecognizer recognizer, NameMatcher matcher, boolean capitalNames) {
        this.recognizer = Objects.requireNonNull(recognizer);
        this.matcher = Objects.requireNonNull(matcher);
        this.capitalNames = capitalNames;
    }

    /**
     * One entry per name box found, top to bottom; unrecognized text is only kept if it carries a level.
     * The text colour depends on the game and the right letter grouping on the emulator's smoothing, so every
     * combination is read and the reading with the most recognized names wins (see {@link ImagePreprocessor}).
     */
    public List<Sighting> scan(BufferedImage image) {
        List<Sighting> best = List.of();
        for (ImagePreprocessor.TextColor textColor : ImagePreprocessor.TextColor.values()) {
            for (boolean joinStrokes : new boolean[]{false, true}) {
                List<Sighting> sightings = new ArrayList<>();
                for (List<Word> line : lines(recognizer.read(ImagePreprocessor.prepare(image, textColor, joinStrokes)))) {
                    toSighting(line).ifPresent(sightings::add);
                }
                if (recognizedCount(sightings) > recognizedCount(best)
                        || (recognizedCount(sightings) == recognizedCount(best) && sightings.size() > best.size())) {
                    best = sightings;
                }
            }
        }
        return best;
    }

    private static long recognizedCount(List<Sighting> sightings) {
        return sightings.stream().filter(sighting -> sighting.pokemon().isPresent()).count();
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
     * A number only counts as the level with an "Lv" sign in front of it ("Lv32", or "Lv" "32"): on real screenshots
     * OCR often misreads the bold level digits (e.g. "29" for "Lv39"), and a wrong level is worse than none.
     */
    Optional<Sighting> toSighting(List<Word> line) {
        List<String> nameTokens = new ArrayList<>();
        OptionalInt level = OptionalInt.empty();
        boolean afterLevelSign = false;
        for (Word word : line) {
            Matcher levelMatcher = LEVEL.matcher(word.text());
            if (levelMatcher.matches()) {
                int value = Integer.parseInt(levelMatcher.group(2));
                boolean signed = levelMatcher.group(1) != null || afterLevelSign;
                if (signed && value >= 1 && value <= MAX_LEVEL && level.isEmpty()) {
                    level = OptionalInt.of(value);
                }
                afterLevelSign = false;
            } else {
                afterLevelSign = LEVEL_SIGN.matcher(word.text()).matches();
                if (!capitalNames || isMostlyCapitals(word.text())) {
                    nameTokens.add(word.text());
                }
            }
        }

        String text = String.join(" ", line.stream().map(Word::text).toList());
        Optional<NameMatcher.Match> best = candidates(nameTokens).stream()
                .map(matcher::match)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(NameMatcher.Match::distance));
        if (best.isPresent()) {
            return Optional.of(new Sighting(text, Optional.of(best.get().pokemon()), level));
        }
        // Looks like a name box ("XYZ Lv12") whose name could not be read: show it so the user can correct it.
        boolean hasLetters = NameMatcher.normalize(String.join("", nameTokens)).length() >= 3;
        return level.isPresent() && hasLetters ? Optional.of(new Sighting(text, Optional.empty(), level)) : Optional.empty();
    }

    static boolean isMostlyCapitals(String word) {
        long letters = word.chars().filter(Character::isLetter).count();
        long capitals = word.chars().filter(Character::isUpperCase).count();
        return letters > 0 && capitals >= letters * MIN_CAPITALS;
    }

    /** Single words, adjacent pairs ("MR." "MIME") and the whole line, in case OCR adds or splits words. */
    private static List<String> candidates(List<String> tokens) {
        List<String> candidates = new ArrayList<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            candidates.add(tokens.get(i) + tokens.get(i + 1));
        }
        if (tokens.size() > 2) {
            candidates.add(String.join("", tokens));
        }
        return candidates;
    }
}
