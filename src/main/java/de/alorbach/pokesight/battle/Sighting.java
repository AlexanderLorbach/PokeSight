package de.alorbach.pokesight.battle;

import java.util.Objects;

/**
 * One opponent as read from the screen: a name box whose text matched a Pokémon.
 *
 * @param text    the raw OCR text of the box, shown to the user next to a corrected Pokémon
 * @param pokemon the matching Pokémon
 */
public record Sighting(String text, PokemonId pokemon) {

    public Sighting {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(pokemon, "pokemon");
    }

    /** Equal Pokémon; the raw text varies with OCR noise and does not count. */
    public boolean sameAs(Sighting other) {
        return pokemon.equals(other.pokemon);
    }
}
