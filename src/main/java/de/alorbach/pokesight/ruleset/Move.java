package de.alorbach.pokesight.ruleset;

import java.util.Map;
import java.util.Objects;

/** A damaging move with its type and base power in the ruleset's generation. */
public record Move(Map<Language, String> name, PokemonType type, int power, Category category) {

    /** Gen 4+ decides per move whether Attack or Special Attack counts. */
    public enum Category { PHYSICAL, SPECIAL }

    public Move {
        name = Map.copyOf(Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(category, "category");
        if (power <= 0) {
            throw new IllegalArgumentException("A damaging move needs a positive power, got " + power);
        }
    }

    public String name(Language language) {
        return name.get(language);
    }
}
