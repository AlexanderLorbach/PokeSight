package de.alorbach.pokesight.battle;

/** A species (national dex number) in a specific form (0 = base form), as listed in the ruleset. */
public record PokemonId(int speciesId, int form) {

    public static PokemonId of(int speciesId) {
        return new PokemonId(speciesId, 0);
    }
}
