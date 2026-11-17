package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.ImagePreprocessor;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads the moves of your active Pokémon from the game's move selection (the fight menu): up to four move names,
 * in a list (Gen 1-3) or a 2 x 2 grid (since Gen 4). Only damaging moves with a fixed power are known to the
 * ruleset, so status moves are skipped; they do not take part in the move ranking anyway.
 *
 * <p>Since Gen 4 every move shows its type as a label next to its PP ("PSYCHIC PP 20/20" under "Psybeam", "PP 35/35
 * FLYING", "Sacred Sword FIGHTING 15/15"); Gen 1-3 show the selected move's type after "TYPE/". Such labels are not
 * moves: "PSYCHIC" would otherwise be read as the move Psychic.
 */
public final class MoveReader {

    private static final int MAX_MOVES = 4;
    /** Move names have up to three words ("Thunder Punch", "Hi Jump Kick"). */
    private static final int MAX_WORDS = 3;
    /** A PP counter: "15/15", also misread ("1S/15", "5/S"). */
    private static final Pattern PP_COUNTER = Pattern.compile("[0-9OoIlSs]{1,2}/[0-9OoIlSs]{1,2}");
    /** The caption before the selected move's type in Gen 1-3 ("TYPE/NORMAL", German "TYP/NORMAL"). */
    private static final Set<String> TYPE_CAPTIONS = Set.of("TYPE", "TYP");
    /** Shortest abbreviation of a type name that counts as its label ("FIGHT", "ELECTR", "PSYCHC" in Black/White). */
    private static final int MIN_TYPE_ABBREVIATION = 4;
    /** Letters a move name needs for the loose match, see {@link #looseDistance}. */
    private static final int MIN_LOOSE_LENGTH = 10;
    /** Half letters by which a loose match must be closer than any other move: two letters. */
    private static final int LOOSE_MARGIN = 4;
    /** Words with fewer letters are icon noise between a type label and its PP ("Or]", "eh", "+"). */
    private static final int MIN_WORD_LETTERS = 3;

    private final TextRecognizer recognizer;
    private final Map<String, Integer> movesByName = new LinkedHashMap<>();
    private final List<String> typeNames;

    public MoveReader(TextRecognizer recognizer, Ruleset ruleset, Language language) {
        this.recognizer = Objects.requireNonNull(recognizer);
        // Older games abbreviate or join words ("ThunderPunch", "DoubleSlap"); without spaces they match again.
        // In move id order: the ruleset's map has no fixed order, and ties must not depend on it.
        new TreeMap<>(ruleset.moves()).forEach((id, move) -> movesByName.putIfAbsent(NameMatcher.normalize(move.name(language)), id));
        typeNames = ruleset.typeChart().types().stream()
                .map((PokemonType type) -> NameMatcher.normalize(ruleset.typeChart().name(type, language)))
                .toList();
    }

    /** Move ids in reading order (left to right, top to bottom), without duplicates; every text colour is tried. */
    public List<Integer> read(BufferedImage image) {
        List<Integer> best = List.of();
        for (ImagePreprocessor.TextColor textColor : ImagePreprocessor.DARK_AND_LIGHT) {
            for (boolean joinStrokes : new boolean[]{false, true}) {
                for (BufferedImage prepared : ImagePreprocessor.prepare(image, textColor, joinStrokes)) {
                    List<Integer> moves = moves(recognizer.read(prepared));
                    if (moves.size() > best.size()) {
                        best = moves;
                    }
                }
            }
        }
        return best;
    }

    private List<Integer> moves(List<Word> words) {
        List<Integer> moves = new ArrayList<>();
        for (List<Word> line : BattleScanner.lines(words)) {
            for (List<Word> box : BattleScanner.boxes(line)) {
                for (List<String> segment : withoutTypeLabels(box, line)) {
                    matchAll(segment).stream()
                            .filter(id -> !moves.contains(id) && moves.size() < MAX_MOVES)
                            .forEach(moves::add);
                }
            }
        }
        return moves;
    }

    /** The words of a box split at its type labels, which are left out. */
    private List<List<String>> withoutTypeLabels(List<Word> box, List<Word> line) {
        List<List<String>> segments = new ArrayList<>();
        List<String> segment = new ArrayList<>();
        for (Word word : box) {
            if (isTypeLabel(line, line.indexOf(word))) {
                segments.add(segment);
                segment = new ArrayList<>();
            } else {
                segment.add(word.text());
            }
        }
        segments.add(segment);
        return segments;
    }

    /**
     * A type name next to a PP counter in its line (skipping OCR noise of icons in between), or after "TYPE/". A move
     * named like its type keeps its name where the label stands beside it: "Psychic PSYCHIC 10/10" (Sword/Shield).
     * One case is not told apart: in Gen 3 a Psychic as the top-right move stands next to the PP of the side panel and
     * is skipped, which is safer than adding Psychic for every Psychic-type move.
     */
    private boolean isTypeLabel(List<Word> line, int index) {
        if (!isTypeName(line.get(index).text())) {
            return false;
        }
        Optional<Word> before = neighbour(line, index, -1);
        Optional<Word> after = neighbour(line, index, 1);
        return before.filter(MoveReader::isPpCounter).isPresent() || after.filter(MoveReader::isPpCounter).isPresent()
                || before.filter(word -> TYPE_CAPTIONS.contains(NameMatcher.normalize(word.text()))).isPresent();
    }

    /** The nearest word in this direction that is a PP counter or has {@value #MIN_WORD_LETTERS} letters or more. */
    private static Optional<Word> neighbour(List<Word> line, int index, int step) {
        for (int i = index + step; i >= 0 && i < line.size(); i += step) {
            Word word = line.get(i);
            if (isPpCounter(word) || NameMatcher.normalize(word.text()).length() >= MIN_WORD_LETTERS) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }

    private static boolean isPpCounter(Word word) {
        return NameMatcher.normalize(word.text()).equals("PP") || PP_COUNTER.matcher(word.text()).find();
    }

    private boolean isTypeName(String text) {
        String candidate = NameMatcher.normalize(text);
        return candidate.length() >= 3 && typeNames.stream().anyMatch(type ->
                NameMatcher.ocrDistance(candidate, type) <= maxDistance(type.length())
                        || (candidate.length() >= MIN_TYPE_ABBREVIATION && type.startsWith(candidate)));
    }

    /**
     * Every move in these adjacent words, in reading order: the best match first, then the words left and right of it.
     * Since Gen 4 two moves can stand in one box: the gap between the columns of the move grid is narrow
     * ("Flamethrower Earthquake" in HeartGold/SoulSilver).
     */
    List<Integer> matchAll(List<String> words) {
        Optional<Match> best = bestMatch(words);
        if (best.isEmpty()) {
            return List.of();
        }
        List<Integer> moves = new ArrayList<>(matchAll(words.subList(0, best.get().start())));
        moves.add(best.get().moveId());
        moves.addAll(matchAll(words.subList(best.get().end(), words.size())));
        return moves;
    }

    /** The best match among runs of up to {@value #MAX_WORDS} adjacent words: "PP 35/35" next to a name is ignored. */
    Optional<Integer> match(List<String> words) {
        return bestMatch(words).map(Match::moveId);
    }

    /** A move name found in words {@code start} (inclusive) to {@code end} (exclusive). */
    private record Match(int moveId, int start, int end) {
    }

    private Optional<Match> bestMatch(List<String> words) {
        Match best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLength = 0;
        for (int start = 0; start < words.size(); start++) {
            StringBuilder text = new StringBuilder();
            for (int end = start; end < Math.min(words.size(), start + MAX_WORDS); end++) {
                text.append(words.get(end));
                String candidate = NameMatcher.normalize(text.toString());
                if (candidate.length() < 3) {
                    continue;
                }
                Optional<Map.Entry<String, Integer>> found = match(candidate);
                if (found.isEmpty()) {
                    continue;
                }
                String name = found.get().getKey();
                int distance = NameMatcher.ocrDistance(candidate, name);
                // On a tie the longer name wins: "Thunder Punch" is not "Thunder" with an extra word.
                if (distance < bestDistance || (distance == bestDistance && name.length() > bestLength)) {
                    best = new Match(found.get().getValue(), start, end + 1);
                    bestDistance = distance;
                    bestLength = name.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The move this text names: the closest name within {@link #maxDistance}, the longer one on a tie. Without one, a
     * long name may be further off if no other move comes close ({@link #looseDistance}): pixel fonts are misread by
     * several letters ("Dreaming Kiss" for Draining Kiss, "Disarmng Yoce" for Disarming Voice in Platinum Together).
     */
    private Optional<Map.Entry<String, Integer>> match(String candidate) {
        Map.Entry<String, Integer> strict = null;
        int strictDistance = Integer.MAX_VALUE;
        Map.Entry<String, Integer> closest = null;
        int closestDistance = Integer.MAX_VALUE;
        int secondDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : movesByName.entrySet()) {
            int distance = NameMatcher.ocrDistance(candidate, entry.getKey());
            int length = entry.getKey().length();
            if (distance <= maxDistance(length) && (distance < strictDistance
                    || (distance == strictDistance && length > strict.getKey().length()))) {
                strict = entry;
                strictDistance = distance;
            }
            if (distance < closestDistance) {
                secondDistance = closestDistance;
                closest = entry;
                closestDistance = distance;
            } else if (distance < secondDistance) {
                secondDistance = distance;
            }
        }
        if (strict != null) {
            return Optional.of(strict);
        }
        boolean looseMatch = closest != null && closestDistance <= looseDistance(closest.getKey().length())
                && secondDistance - closestDistance >= LOOSE_MARGIN;
        return looseMatch ? Optional.of(closest) : Optional.empty();
    }

    /**
     * In half letters: a quarter of the letters of a name of {@value #MIN_LOOSE_LENGTH} letters or more may be misread
     * (3 of "DRAININGKISS"), none beyond {@link #maxDistance} for shorter names. Real move names are at least 4 half
     * letters apart (Sacred Sword, Secret Sword), hence the margin of {@value #LOOSE_MARGIN} to the next move.
     */
    static int looseDistance(int nameLength) {
        return nameLength < MIN_LOOSE_LENGTH ? maxDistance(nameLength) : nameLength / 2;
    }

    /**
     * In half letters (see {@link NameMatcher#ocrDistance}): one wrong letter at most, none for short names. Stricter
     * than for Pokémon names, because a wrong move would end up in your team; the type labels next to the moves
     * ("POISON") would otherwise match moves like Poison Jab.
     */
    static int maxDistance(int nameLength) {
        if (nameLength < 4) {
            return 0;
        }
        return nameLength < 6 ? 1 : 2;
    }
}
