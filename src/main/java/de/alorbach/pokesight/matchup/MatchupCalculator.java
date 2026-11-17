package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.EnemyAnalysis.SwitchIn;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;
import de.alorbach.pokesight.ruleset.Species.Stat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Works out which attack types hurt a defender, which of the team's moves hit it hardest and which team member is the
 * best switch-in against it. By types and base stats: abilities, items, levels and stat changes are not known.
 */
public final class MatchupCalculator {

    private static final double STAB = 1.5;
    /** Multiplier an immune switch-in counts with: still the best defensively, without dividing by zero. */
    private static final double MIN_TAKEN = 0.25;

    private final Ruleset ruleset;

    public MatchupCalculator(Ruleset ruleset) {
        this.ruleset = ruleset;
    }

    public Ruleset ruleset() {
        return ruleset;
    }

    /** @return empty if the species is not part of the ruleset */
    public Optional<EnemyAnalysis> analyze(PokemonId id, Language language, Team team) {
        return analyze(id, language, team, null);
    }

    /**
     * @param tera the Tera type it terastallized into (Scarlet/Violet), null if it did not: it then has that type
     *             alone, but still attacks with its own types too
     * @return empty if the species is not part of the ruleset
     */
    public Optional<EnemyAnalysis> analyze(PokemonId id, Language language, Team team, PokemonType tera) {
        return ruleset.findSpecies(id.speciesId()).map(species -> {
            Species.Form form = species.form(id.form());
            List<PokemonType> types = tera == null ? form.types() : List.of(tera);
            List<PokemonType> threats = new ArrayList<>(form.types());
            if (tera != null && !threats.contains(tera)) {
                threats.add(tera);
            }
            List<MoveOption> moves = rankMoves(team, form, types);
            return new EnemyAnalysis(form.name(language), types, tera != null, defensiveMatchup(types), moves,
                    switchIns(team, form, threats, moves));
        });
    }

    /** Attack types grouped by the multiplier they deal to these defending types (1x left out). */
    public Matchup defensiveMatchup(List<PokemonType> defenderTypes) {
        TreeMap<Double, List<PokemonType>> groups = new TreeMap<>();
        for (PokemonType attackType : ruleset.typeChart().types()) {
            double multiplier = ruleset.typeChart().multiplier(attackType, defenderTypes);
            if (multiplier != 1) {
                groups.computeIfAbsent(multiplier, k -> new ArrayList<>()).add(attackType);
            }
        }
        return new Matchup(groups);
    }

    /**
     * The team's moves against a defender, strongest first: base power x type effectiveness x 1.5 if the move's type
     * matches its user (STAB) x the user's attacking base stat / the defender's defending base stat. Level, natures
     * and stat changes are not known; unknown Pokémon or moves are skipped.
     */
    public List<MoveOption> rankMoves(Team team, Species.Form defender, List<PokemonType> defenderTypes) {
        List<MoveOption> options = new ArrayList<>();
        for (Team.Member member : team.members()) {
            Optional<Species.Form> user = form(member.pokemon());
            if (user.isEmpty()) {
                continue;
            }
            for (int moveId : member.moves()) {
                Optional<Move> move = ruleset.findMove(moveId);
                if (move.isEmpty()) {
                    continue;
                }
                PokemonType type = move.get().type();
                double effectiveness = ruleset.typeChart().multiplier(type, defenderTypes);
                boolean stab = user.get().types().contains(type);
                boolean physical = move.get().category() == Move.Category.PHYSICAL;
                double statRatio = (double) user.get().stat(physical ? Stat.ATTACK : Stat.SPECIAL_ATTACK)
                        / defender.stat(physical ? Stat.DEFENSE : Stat.SPECIAL_DEFENSE);
                double score = move.get().power() * effectiveness * (stab ? STAB : 1) * statRatio;
                options.add(new MoveOption(member.pokemon(), moveId, effectiveness, stab, score));
            }
        }
        options.sort(Comparator.comparingDouble(MoveOption::score).reversed());
        return options;
    }

    /**
     * Every team member against an attacker, best switch-in first: the most damage dealt for the damage taken (see
     * {@link SwitchIn#rating}); on a tie (no moves entered) the one that takes the least.
     *
     * @param attackTypes the attacker's types it attacks with (its own, and a Tera type)
     * @param moves       the team's moves against it, strongest first ({@link #rankMoves})
     */
    public List<SwitchIn> switchIns(Team team, Species.Form attacker, List<PokemonType> attackTypes, List<MoveOption> moves) {
        List<SwitchIn> result = new ArrayList<>();
        for (Team.Member member : team.members()) {
            Optional<Species.Form> form = form(member.pokemon());
            if (form.isEmpty()) {
                continue;
            }
            Map<PokemonType, Double> taken = new LinkedHashMap<>();
            for (PokemonType attackType : attackTypes) {
                taken.put(attackType, ruleset.typeChart().multiplier(attackType, form.get().types()));
            }
            double worst = taken.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
            Optional<MoveOption> bestMove = moves.stream()
                    .filter(option -> option.user().equals(member.pokemon()) && option.score() > 0)
                    .findFirst();
            double statRatio = Math.max(
                    (double) attacker.stat(Stat.ATTACK) / form.get().stat(Stat.DEFENSE),
                    (double) attacker.stat(Stat.SPECIAL_ATTACK) / form.get().stat(Stat.SPECIAL_DEFENSE));
            double damageTaken = Math.max(worst, MIN_TAKEN) * statRatio;
            double rating = bestMove.map(MoveOption::score).orElse(0.0) / damageTaken;
            result.add(new SwitchIn(member.pokemon(), taken, worst, bestMove, damageTaken, rating));
        }
        result.sort(Comparator.comparingDouble(SwitchIn::rating).reversed().thenComparingDouble(SwitchIn::damageTaken));
        return result;
    }

    private Optional<Species.Form> form(PokemonId pokemon) {
        return ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()));
    }
}
