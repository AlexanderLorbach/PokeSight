package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.ImagePreprocessor;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ocr.Word;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the moves of your active Pokémon from the game's move selection (the fight menu): up to four move names,
 * in a list (Gen 1-3) or a 2 x 2 grid (since Gen 4). Only damaging moves with a fixed power are known to the
 * ruleset, so status moves are skipped; they do not take part in the move ranking anyway.
 */
public final class MoveReader {

    private static final int MAX_MOVES = 4;
    /** Move names have up to three words ("Thunder Punch", "Hi Jump Kick"). */
    private static final int MAX_WORDS = 3;

    private final TextRecognizer recognizer;
    private final Map<String, Integer> movesByName = new LinkedHashMap<>();

    public MoveReader(TextRecognizer recognizer, Ruleset ruleset, Language language) {
        this.recognizer = Objects.requireNonNull(recognizer);
        // Older games abbreviate or join words ("ThunderPunch", "DoubleSlap"); without spaces they match again.
        ruleset.moves().forEach((id, move) -> movesByName.putIfAbsent(NameMatcher.normalize(move.name(language)), id));
    }

    /** Move ids in reading order (left to right, top to bottom), without duplicates; every text colour is tried. */
    public List<Integer> read(BufferedImage image) {
        List<Integer> best = List.of();
        for (ImagePreprocessor.TextColor textColor : ImagePreprocessor.TextColor.values()) {
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
                match(box.stream().map(Word::text).toList())
                        .filter(id -> !moves.contains(id) && moves.size() < MAX_MOVES)
                        .ifPresent(moves::add);
            }
        }
        return moves;
    }

    /** The best match among runs of up to {@value #MAX_WORDS} adjacent words: "PP 35/35" next to a name is ignored. */
    Optional<Integer> match(List<String> words) {
        Integer best = null;
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
                for (Map.Entry<String, Integer> entry : movesByName.entrySet()) {
                    int distance = NameMatcher.ocrDistance(candidate, entry.getKey());
                    // On a tie the longer name wins: "Thunder Punch" is not "Thunder" with an extra word.
                    boolean better = distance < bestDistance || (distance == bestDistance && entry.getKey().length() > bestLength);
                    if (distance <= maxDistance(entry.getKey().length()) && better) {
                        best = entry.getValue();
                        bestDistance = distance;
                        bestLength = entry.getKey().length();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
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
