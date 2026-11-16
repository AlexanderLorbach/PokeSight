package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ui.SearchField.Option;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionsTest {

    private static final Predicate<Move.Category> PHYSICAL = category -> category == Move.Category.PHYSICAL;
    private static final Predicate<Move.Category> SPECIAL = category -> category == Move.Category.SPECIAL;
    private static final List<Option<Move.Category>> MOVES = List.of(
            Option.of("Charge Beam", "", Move.Category.SPECIAL),
            Option.of("Thunder", "", Move.Category.SPECIAL),
            Option.of("Thunder Fang", "", Move.Category.PHYSICAL),
            Option.of("Thunder Punch", "", Move.Category.PHYSICAL),
            Option.of("Thunderbolt", "", Move.Category.SPECIAL),
            Option.of("Wild Charge", "", Move.Category.PHYSICAL));

    @Test
    void listsMovesOfThePreferredKindFirstAmongEqualMatches() {
        assertThat(labels("thun", PHYSICAL)).containsExactly("Thunder Fang", "Thunder Punch", "Thunder", "Thunderbolt");
        assertThat(labels("thun", SPECIAL)).containsExactly("Thunder", "Thunderbolt", "Thunder Fang", "Thunder Punch");
    }

    @Test
    void namesStartingWithTheInputStayBeforePreferredOnesContainingIt() {
        assertThat(labels("charge", PHYSICAL)).containsExactly("Charge Beam", "Wild Charge");
    }

    @Test
    void withoutPreferenceKeepsTheGivenOrder() {
        assertThat(labels("thun", category -> false)).containsExactly("Thunder", "Thunder Fang", "Thunder Punch", "Thunderbolt");
        assertThat(labels("", PHYSICAL)).isEmpty();
    }

    private static List<String> labels(String text, Predicate<Move.Category> preferred) {
        return Suggestions.matching(MOVES, text, preferred).stream().map(Option::label).toList();
    }
}
