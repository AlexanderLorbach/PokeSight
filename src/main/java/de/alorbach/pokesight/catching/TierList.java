package de.alorbach.pokesight.catching;

import de.alorbach.pokesight.YamlResources;

import java.util.List;
import java.util.Objects;

/**
 * A Smogon in-game tier list ({@code tiers/<id>.yaml}): how worthwhile each evolution line is for a playthrough,
 * judged by availability, typing, stats, movepool and the major battles.
 *
 * @param lastDex the highest national dex number of the game; newer Pokémon (in fan games) are not rated
 * @param starred the tiers that earn the catch star, best first
 */
public record TierList(String name, String author, String url, int lastDex, List<String> starred, List<Entry> entries) {

    /**
     * @param species the Pokémon the entry names
     * @param upTo    for an entry about one evolution path (Abra (Trade), Eevee (Vaporeon)) the last stage it rates;
     *                null if it rates the whole line
     * @param games   the games the entry is for (version exclusives of a list for several games); null for all
     */
    public record Entry(String tier, String name, int species, Integer upTo, List<String> games) {

        public Entry {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(name, "name");
            games = games == null ? null : List.copyOf(games);
        }

        boolean appliesTo(String gameId) {
            return games == null || games.contains(gameId);
        }

        /** Its species with the earlier stages, and the later stages (only up to {@link #upTo} if set). */
        boolean covers(int speciesId, Pokedex pokedex) {
            if (pokedex.isAncestorOrEqual(speciesId, species)) {
                return true;
            }
            return pokedex.isAncestorOrEqual(species, speciesId)
                    && (upTo == null || pokedex.isAncestorOrEqual(speciesId, upTo));
        }
    }

    public TierList {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        starred = List.copyOf(Objects.requireNonNull(starred, "starred"));
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (starred.isEmpty()) {
            throw new IllegalArgumentException("A tier list needs at least one starred tier");
        }
    }

    public static TierList load(String id) {
        return YamlResources.read("tiers/" + id + ".yaml", TierList.class);
    }
}
