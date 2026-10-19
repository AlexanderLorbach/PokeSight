package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the species whose name is closest to an OCR reading. There are only a few hundred
 * names, so a small edit distance absorbs typical OCR mistakes ("GENGAP" -> Gengar).
 */
public final class NameMatcher {

    public record Match(PokemonId pokemon, int distance) {
    }

    private final Map<String, Integer> speciesByName = new LinkedHashMap<>();

    public NameMatcher(Ruleset ruleset, Language language) {
        // Battle screens show the species name, never the form, so only base names are matched.
        ruleset.species().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> speciesByName.putIfAbsent(normalize(entry.getValue().name(language)), entry.getKey()));
    }

    public Optional<Match> match(String text) {
        String candidate = normalize(text);
        if (candidate.length() < 3) {
            return Optional.empty();
        }
        Match best = null;
        for (Map.Entry<String, Integer> entry : speciesByName.entrySet()) {
            int distance = levenshtein(candidate, entry.getKey());
            if (distance <= maxDistance(entry.getKey().length()) && (best == null || distance < best.distance())) {
                best = new Match(PokemonId.of(entry.getValue()), distance);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Upper-case letters only, without accents: "Mr. Mime" -> "MRMIME", "Nidoran♀" -> "NIDORAN".
     * Digits that OCR typically confuses with letters are mapped back to them.
     */
    public static String normalize(String text) {
        String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.toUpperCase(Locale.ROOT)
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('8', 'B')
                .replaceAll("[^A-Z]", "");
    }

    /** Short names must match exactly, longer ones may have one or two misread letters. */
    static int maxDistance(int nameLength) {
        if (nameLength < 4) {
            return 0;
        }
        return nameLength <= 6 ? 1 : 2;
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
