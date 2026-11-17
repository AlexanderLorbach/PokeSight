package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Learnset;
import de.alorbach.pokesight.ruleset.Learnsets;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The damaging moves Pokémon can learn in the game: its learnsets joined with the ruleset's moves. Moves the ruleset
 * does not know (no fixed power, like Seismic Toss) are left out.
 *
 * <p>A game that takes Pokémon from newer games (Platinum Together's MODERN POKéMON) has no learnsets of its own for
 * them: those come from the newer games' learnsets, the first one that has the Pokémon.
 */
public final class MovePool {

    /**
     * A move a Pokémon can learn.
     *
     * @param ways   how, easiest first (level-up, machine, tutor, reminder, egg)
     * @param source the learnsets it comes from: the game's own, or a newer game's for a Pokémon the game lacks
     */
    public record Learnable(PokemonId pokemon, int moveId, Move move, List<Learnset.Way> ways, String source) {
        public Learnable {
            ways = List.copyOf(ways);
        }

        /** Only by breeding: a Pokémon already in the team cannot learn it (in most games). */
        public boolean eggOnly() {
            return ways.stream().allMatch(way -> way.method() == Learnset.Method.EGG);
        }
    }

    private final Ruleset ruleset;
    private final List<Learnsets> sources;

    /**
     * @param sources the game's learnsets first, then those of newer games for Pokémon it lacks; at least one
     */
    public MovePool(Ruleset ruleset, List<Learnsets> sources) {
        this.ruleset = Objects.requireNonNull(ruleset);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least the game's learnsets are needed");
        }
        this.sources = List.copyOf(sources);
    }

    public MovePool(Learnsets learnsets, Ruleset ruleset) {
        this(ruleset, List.of(learnsets));
    }

    /** The game's own learnsets' id. */
    public String gameLearnsets() {
        return sources.getFirst().id();
    }

    /**
     * The game's machine that teaches the move ("TM26"); also for a Pokémon from a newer game, which learns it from
     * the game's own machines. Empty if no machine of the game teaches it.
     */
    public Optional<String> machine(int moveId) {
        return sources.getFirst().machine(moveId);
    }

    /** Whether some learnsets have this Pokémon. */
    public boolean knows(PokemonId pokemon) {
        return learnset(pokemon).isPresent();
    }

    /** Its damaging moves, strongest first; empty if no learnsets have it. */
    public List<Learnable> of(PokemonId pokemon) {
        List<Learnable> moves = new ArrayList<>();
        for (Learnsets source : sources) {
            Optional<Learnset> learnset = source.find(pokemon.speciesId(), pokemon.form());
            if (learnset.isPresent()) {
                learnset.get().ways().forEach((moveId, ways) -> ruleset.findMove(moveId)
                        .ifPresent(move -> moves.add(new Learnable(pokemon, moveId, move, ways, source.id()))));
                break;
            }
        }
        moves.sort(Comparator.comparingInt((Learnable learnable) -> learnable.move().power()).reversed()
                .thenComparing(learnable -> learnable.ways().getFirst().method()));
        return moves;
    }

    /** Every Pokémon and form of the ruleset with its moves, in dex order: the whole game's list. */
    public List<Learnable> all() {
        List<Learnable> all = new ArrayList<>();
        ruleset.species().keySet().stream().sorted().forEach(speciesId -> {
            all.addAll(of(new PokemonId(speciesId, 0)));
            // Forms only where they learn other moves than the species (Alolan Raichu), so no move is listed twice.
            Species species = ruleset.species().get(speciesId);
            species.forms().keySet().stream().sorted()
                    .filter(form -> sources.stream().anyMatch(source -> source.pokemon().containsKey(speciesId)
                            && source.pokemon().get(speciesId).forms().containsKey(form)))
                    .forEach(form -> all.addAll(of(new PokemonId(speciesId, form))));
        });
        return all;
    }

    /**
     * The move of this type that would hit hardest for this Pokémon: power times the attacking stat it uses (Attack
     * for physical, Sp. Atk for special moves), easier to learn first on a tie. Egg moves are left out: a Pokémon
     * already caught cannot learn them.
     */
    public Optional<Learnable> best(PokemonId pokemon, PokemonType type) {
        Optional<Species.Form> form = ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()));
        return of(pokemon).stream()
                .filter(learnable -> learnable.move().type() == type && !learnable.eggOnly())
                .max(Comparator.comparingInt((Learnable learnable) -> learnable.move().power() * attackStat(form, learnable.move()))
                        .thenComparing(learnable -> learnable.ways().getFirst().method(), Comparator.reverseOrder()));
    }

    private Optional<Learnset> learnset(PokemonId pokemon) {
        return sources.stream().flatMap(source -> source.find(pokemon.speciesId(), pokemon.form()).stream()).findFirst();
    }

    private static int attackStat(Optional<Species.Form> form, Move move) {
        return form.map(found -> found.stat(move.category() == Move.Category.PHYSICAL
                ? Species.Stat.ATTACK : Species.Stat.SPECIAL_ATTACK)).orElse(1);
    }
}
