package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.NameMatcher;
import de.alorbach.pokesight.ui.SearchField.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Which options a {@link SearchField} suggests for its text, and in which order. Kept apart from the JavaFX control. */
final class Suggestions {

    static final int MAX = 10;

    private Suggestions() {
    }

    /**
     * Names starting with the text first, then names containing it; within each, the preferred options first, each
     * part in the options' order (alphabetical). At most {@link #MAX}; none for empty text.
     */
    static <T> List<Option<T>> matching(List<Option<T>> options, String text, Predicate<T> preferred) {
        String key = NameMatcher.normalize(text);
        if (key.isEmpty()) {
            return List.of();
        }
        List<Option<T>> starting = options.stream().filter(option -> option.key().startsWith(key)).toList();
        List<Option<T>> containing = options.stream()
                .filter(option -> !option.key().startsWith(key) && option.key().contains(key)).toList();
        List<Option<T>> result = new ArrayList<>();
        for (List<Option<T>> group : List.of(starting, containing)) {
            group.stream().filter(option -> preferred.test(option.value())).forEach(result::add);
            group.stream().filter(option -> !preferred.test(option.value())).forEach(result::add);
        }
        return List.copyOf(result.subList(0, Math.min(MAX, result.size())));
    }
}
