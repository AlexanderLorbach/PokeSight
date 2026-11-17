package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the species whose name is closest to an OCR reading. There are only a few hundred
 * names, so a small edit distance absorbs typical OCR mistakes ("GENGAP" -> Gengar).
 */
public final class NameMatcher {

    /** @param distance OCR distance in half letters, see {@link #ocrDistance} */
    public record Match(PokemonId pokemon, int distance) {
    }

    /**
     * Letters OCR mixes up in the small game fonts (seen on the screenshot tests: "SCHISSY" for SCHIGGY, "HEEDLE" for
     * WEEDLE, "Mecuth" for Meowth). Swapping two letters of a group costs half a letter.
     */
    private static final List<String> LOOK_ALIKES = List.of("GSC", "OCDQ", "UVWY", "ILTJ", "EF", "HWNM", "BR");
    /** Names from this length on may be found loosely among the preferred species; shorter ones need a strict match. */
    private static final int MIN_LOOSE_LENGTH = 5;
    /** Half letters by which the closest preferred name must beat the next one to be taken loosely (two look-alikes). */
    private static final int LOOSE_MARGIN = 4;
    /** Compiled once: {@link #normalize} runs for every name of the ruleset when the suggestion lists are built. */
    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Z]");

    private final Map<String, PokemonId> pokemonByName = new LinkedHashMap<>();
    private final Map<String, String> nameByKey = new LinkedHashMap<>();

    public NameMatcher(Ruleset ruleset, Language language) {
        this(ruleset, language, null);
    }

    /** @param region the game's region; a species with a form of that region is matched as that form (may be null) */
    public NameMatcher(Ruleset ruleset, Language language, String region) {
        // Battle screens show the species name, never the form, so only base names are matched.
        ruleset.species().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getValue().name(language);
                    pokemonByName.putIfAbsent(normalize(name), new PokemonId(entry.getKey(), entry.getValue().regionalForm(region)));
                    nameByKey.putIfAbsent(normalize(name), name);
                });
    }

    /**
     * The name, spelled as in the game data ("Nidoran♀"), if the text has as many letters as one name and differs from it in at
     * most {@code maxDistance} half letters (wrong letters only) and no other name is as close.
     */
    public Optional<String> nearName(String text, int maxDistance) {
        String candidate = normalize(text);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String key : nameByKey.keySet()) {
            if (key.length() == candidate.length()) {
                int distance = ocrDistance(candidate, key);
                if (distance < bestDistance) {
                    best = key;
                    bestDistance = distance;
                    tie = false;
                } else if (distance == bestDistance) {
                    tie = true;
                }
            }
        }
        return best != null && bestDistance <= maxDistance && !tie ? Optional.of(nameByKey.get(best)) : Optional.empty();
    }

    public Optional<Match> match(String text) {
        String candidate = normalize(text);
        if (candidate.length() < 3) {
            return Optional.empty();
        }
        Match best = null;
        for (Map.Entry<String, PokemonId> entry : pokemonByName.entrySet()) {
            int distance = ocrDistance(candidate, entry.getKey());
            if (distance <= 2 * maxDistance(entry.getKey().length()) && (best == null || distance < best.distance())) {
                best = new Match(entry.getValue(), distance);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The player's team as their own name boxes show it: the species, and the nicknames of members that have one (the
     * box shows the nickname instead of the species).
     *
     * @param nicknames nickname as entered → the member it belongs to
     */
    public record Preferred(Set<Integer> species, Map<String, PokemonId> nicknames) {
        public static final Preferred NONE = new Preferred(Set.of(), Map.of());

        public Preferred {
            species = Set.copyOf(species);
            nicknames = Map.copyOf(nicknames);
        }

        public static Preferred species(Set<Integer> species) {
            return new Preferred(species, Map.of());
        }

        boolean isEmpty() {
            return species.isEmpty() && nicknames.isEmpty();
        }
    }

    /** See {@link #match(String, Preferred)}, without nicknames. */
    public Optional<Match> match(String text, Set<Integer> preferred) {
        return match(text, Preferred.species(preferred));
    }

    /**
     * Like {@link #match(String)}, but the names of the preferred species (the player's team, read in their own name
     * boxes) and the team's nicknames are also found with up to half their letters misread, as long as no other
     * team member's name is nearly as close: among six names that is still unambiguous, among all species it is not.
     * Any other species needs a strict match and wins only if it is closer (an ally's Pokémon in a multiplayer double
     * battle).
     */
    public Optional<Match> match(String text, Preferred preferred) {
        Optional<Match> strict = match(text);
        if (preferred.isEmpty()) {
            return strict;
        }
        Optional<Match> loose = preferredMatch(normalize(text), preferred);
        if (loose.isPresent() && (strict.isEmpty() || loose.get().distance() <= strict.get().distance())) {
            return loose;
        }
        return strict;
    }

    private Optional<Match> preferredMatch(String candidate, Preferred preferred) {
        if (candidate.length() < 3) {
            return Optional.empty();
        }
        // The names a team member may show: its species name and its nickname.
        Map<String, PokemonId> names = new LinkedHashMap<>();
        pokemonByName.forEach((name, pokemon) -> {
            if (preferred.species().contains(pokemon.speciesId())) {
                names.put(name, pokemon);
            }
        });
        preferred.nicknames().forEach((nickname, pokemon) -> {
            String key = normalize(nickname);
            if (!key.isEmpty()) {
                names.putIfAbsent(key, pokemon);
            }
        });
        // Closest name per team member: a member's species name and nickname do not compete with each other.
        Map<PokemonId, Integer> distances = new LinkedHashMap<>();
        Map<PokemonId, String> closest = new LinkedHashMap<>();
        names.forEach((name, pokemon) -> {
            int distance = ocrDistance(candidate, name);
            if (distance < distances.getOrDefault(pokemon, Integer.MAX_VALUE)) {
                distances.put(pokemon, distance);
                closest.put(pokemon, name);
            }
        });
        Optional<Map.Entry<PokemonId, Integer>> best = distances.entrySet().stream().min(Map.Entry.comparingByValue());
        if (best.isEmpty()) {
            return Optional.empty();
        }
        int bestDistance = best.get().getValue();
        int second = distances.entrySet().stream().filter(entry -> !entry.getKey().equals(best.get().getKey()))
                .mapToInt(Map.Entry::getValue).min().orElse(Integer.MAX_VALUE);
        if (bestDistance > looseDistance(closest.get(best.get().getKey()).length()) || second - bestDistance < LOOSE_MARGIN) {
            return Optional.empty();
        }
        return Optional.of(new Match(best.get().getKey(), bestDistance));
    }

    /** Half letters a preferred name may differ by: half its letters wrong, short names as strictly as ever. */
    static int looseDistance(int nameLength) {
        return nameLength < MIN_LOOSE_LENGTH ? 2 * maxDistance(nameLength) : nameLength;
    }

    /**
     * Upper-case letters only, without accents: "Mr. Mime" -> "MRMIME", "Nidoran♀" -> "NIDORAN".
     * Digits that OCR typically confuses with letters are mapped back to them.
     */
    public static String normalize(String text) {
        String withoutAccents = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String letters = withoutAccents.toUpperCase(Locale.ROOT)
                .replace('0', 'O')
                .replace('1', 'I')
                .replace('5', 'S')
                .replace('8', 'B');
        return NON_LETTERS.matcher(letters).replaceAll("");
    }

    /** Short names must match exactly, longer ones may have one or two misread letters. */
    static int maxDistance(int nameLength) {
        if (nameLength < 4) {
            return 0;
        }
        return nameLength <= 6 ? 1 : 2;
    }

    /**
     * Edit distance in half letters: a missing, extra or wrong letter costs 2, a look-alike letter 1 (see
     * {@link #LOOK_ALIKES}).
     */
    static int ocrDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = 2 * j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = 2 * i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + substitutionCost(a.charAt(i - 1), b.charAt(j - 1));
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 2);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static int substitutionCost(char a, char b) {
        if (a == b) {
            return 0;
        }
        return LOOK_ALIKES.stream().anyMatch(group -> group.indexOf(a) >= 0 && group.indexOf(b) >= 0) ? 1 : 2;
    }
}
