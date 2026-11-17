package de.alorbach.pokesight.catching;

import de.alorbach.pokesight.YamlResources;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Evolutions and rare groups (legendary, ...) of all species, by national dex number ({@code pokedex.yaml}). Species
 * a ROM hack added after {@link #lastDex} are unknown: no evolutions, no group.
 */
public record Pokedex(int lastDex, Map<Integer, Entry> species) {

    /** Pokémon met once or twice per game, which are always worth catching. */
    public enum Group {
        LEGENDARY("Legendary Pokémon"), MYTHICAL("Mythical Pokémon"), ULTRA_BEAST("Ultra Beast"), PARADOX("Paradox Pokémon");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param from  the species it evolves from; null for a first stage
     * @param group null for an ordinary Pokémon
     */
    public record Entry(Integer from, Group group) {
    }

    private static final Entry FIRST_STAGE = new Entry(null, null);

    public Pokedex {
        species = Map.copyOf(Objects.requireNonNull(species, "species"));
        if (lastDex <= 0) {
            throw new IllegalArgumentException("lastDex must be positive, got " + lastDex);
        }
    }

    public static Pokedex load() {
        return YamlResources.read("pokedex.yaml", Pokedex.class);
    }

    /** The bundled Pokédex, loaded once on first use. */
    public static Pokedex shared() {
        return Holder.POKEDEX;
    }

    private static final class Holder {
        static final Pokedex POKEDEX = load();
    }

    public boolean knows(int speciesId) {
        return speciesId >= 1 && speciesId <= lastDex;
    }

    /** The species it evolves from, or 0 for a first stage or an unknown species. */
    public int evolvesFrom(int speciesId) {
        Integer from = entry(speciesId).from();
        return from == null ? 0 : from;
    }

    /** Its rare group (legendary, ...); empty for an ordinary or unknown species. */
    public Optional<Group> group(int speciesId) {
        return Optional.ofNullable(entry(speciesId).group());
    }

    /** Whether {@code ancestor} is {@code speciesId} or one of its earlier stages (Abra for Alakazam). */
    public boolean isAncestorOrEqual(int ancestor, int speciesId) {
        for (int current = speciesId; current != 0; current = evolvesFrom(current)) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private Entry entry(int speciesId) {
        return knows(speciesId) ? species.getOrDefault(speciesId, FIRST_STAGE) : FIRST_STAGE;
    }
}
