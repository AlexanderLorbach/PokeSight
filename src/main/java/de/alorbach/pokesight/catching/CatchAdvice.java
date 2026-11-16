package de.alorbach.pokesight.catching;

import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which Pokémon of a game are worth catching (the star next to a name), and why:
 * <ol>
 *   <li>legendary and mythical Pokémon, Ultra Beasts and Paradox Pokémon, always;</li>
 *   <li>else the starred tiers of the game's Smogon in-game tier list;</li>
 *   <li>Pokémon no list rates (games without one, and Pokémon newer than the list's game): the {@value #TOP_PER_TYPE}
 *       best final evolutions of each type by base stat total (ties included), the groups above left out.</li>
 * </ol>
 * A star always goes to the earlier stages too: Magikarp gets Gyarados's. Species a ROM hack added itself (Megas,
 * event battle Pokémon) get none.
 */
public final class CatchAdvice {

    static final int TOP_PER_TYPE = 5;

    private final Map<Integer, String> reasons = new HashMap<>();

    CatchAdvice(String gameId, TierList tierList, Ruleset ruleset, Pokedex pokedex, Language language) {
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(pokedex, "pokedex");
        addBaseStatLeaders(tierList, ruleset, pokedex, language);
        if (tierList != null) {
            addTierList(gameId, tierList, ruleset, pokedex); // a list's verdict beats raw stats
        }
        for (int speciesId : ruleset.species().keySet()) {
            pokedex.group(speciesId).ifPresent(group -> reasons.put(speciesId, group.label()));
        }
    }

    /** The advice for a game, with its tier list if it has one. */
    public static CatchAdvice forGame(GameDefinition game, Ruleset ruleset, Language language) {
        TierList tierList = game.tierList() == null ? null : TierList.load(game.tierList());
        return new CatchAdvice(game.id(), tierList, ruleset, PokedexHolder.POKEDEX, language);
    }

    /** Why this species is worth catching; empty if it is not starred. */
    public Optional<String> reason(int speciesId) {
        return Optional.ofNullable(reasons.get(speciesId));
    }

    private void addTierList(String gameId, TierList tierList, Ruleset ruleset, Pokedex pokedex) {
        for (int speciesId : ruleset.species().keySet()) {
            if (!pokedex.knows(speciesId)) {
                continue;
            }
            tierList.starred().stream()
                    .flatMap(tier -> tierList.entries().stream().filter(entry -> entry.tier().equals(tier)))
                    .filter(entry -> entry.appliesTo(gameId) && entry.covers(speciesId, pokedex))
                    .findFirst()
                    .ifPresent(entry -> reasons.put(speciesId, "Smogon in-game tier " + entry.tier() + ": " + entry.name()
                            + "\n" + tierList.name() + " (" + tierList.author() + ")"));
        }
    }

    private void addBaseStatLeaders(TierList tierList, Ruleset ruleset, Pokedex pokedex, Language language) {
        Map<Integer, Species> species = ruleset.species();
        // Final in this game: Golbat in Gen 1, where Crobat does not exist yet.
        Set<Integer> evolving = species.keySet().stream().map(pokedex::evolvesFrom).collect(Collectors.toSet());
        List<Integer> finals = species.keySet().stream()
                .filter(id -> pokedex.knows(id) && !evolving.contains(id))
                .filter(id -> pokedex.group(id).isEmpty())
                .sorted(Comparator.comparingInt((Integer id) -> total(species.get(id))).reversed().thenComparing(id -> id))
                .toList();
        // Final evolution -> the types it is among the best of, in type chart order.
        Map<Integer, List<PokemonType>> leaders = new LinkedHashMap<>();
        for (PokemonType type : ruleset.typeChart().types()) {
            List<Integer> ofType = finals.stream().filter(id -> species.get(id).types().contains(type)).toList();
            if (ofType.isEmpty()) {
                continue;
            }
            int cutoff = total(species.get(ofType.get(Math.min(TOP_PER_TYPE, ofType.size()) - 1)));
            ofType.stream().filter(id -> total(species.get(id)) >= cutoff)
                    .forEach(id -> leaders.computeIfAbsent(id, key -> new ArrayList<>()).add(type));
        }
        // Strongest first, so an earlier stage of several leaders (Eevee) names the best of them.
        finals.stream().filter(leaders::containsKey).forEach(id -> {
            Species leader = species.get(id);
            String reason = "Top " + TOP_PER_TYPE + " " + leader.types().stream().filter(leaders.get(id)::contains)
                    .map(type -> ruleset.typeChart().name(type, language)).collect(Collectors.joining("/"))
                    + " Pokémon by base stats: " + leader.name(language) + " (" + total(leader) + ")";
            for (int stage = id; stage != 0; stage = pokedex.evolvesFrom(stage)) {
                if (tierList == null || stage > tierList.lastDex()) { // the list rates the older Pokémon itself
                    reasons.putIfAbsent(stage, reason);
                }
            }
        });
    }

    private static int total(Species species) {
        return species.stats().stream().mapToInt(Integer::intValue).sum();
    }

    /** Loaded on first use; the same for every game. */
    private static final class PokedexHolder {
        static final Pokedex POKEDEX = Pokedex.load();
    }
}
