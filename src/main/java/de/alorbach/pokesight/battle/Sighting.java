package de.alorbach.pokesight.battle;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One opponent's name box as read from the screen.
 *
 * @param text    the raw OCR text of the line, shown to the user when the name was not recognized
 * @param pokemon the matching Pokémon, empty if the text matched no name
 */
public record Sighting(String text, Optional<PokemonId> pokemon, OptionalInt level) {

    public Sighting {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(pokemon, "pokemon");
        Objects.requireNonNull(level, "level");
    }

    /** Equal Pokémon and level; the raw text varies with OCR noise and does not count. */
    public boolean sameAs(Sighting other) {
        return pokemon.equals(other.pokemon) && level.equals(other.level);
    }
}
