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
 * Works out which attack types hurt a defender, which of the team's moves hit it hardest and which team member takes
 * its attacks best. By types alone: abilities, items and stat changes are not known.
 */
public final class MatchupCalculator {

    private static final double STAB = 1.5;

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
            return new EnemyAnalysis(form.name(language), types, tera != null, defensiveMatchup(types),
                    rankMoves(team, form, types), switchIns(team, threats));
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

    /** How each team member takes attacks of these types, safest first: by the worst multiplier, then by the sum. */
    public List<SwitchIn> switchIns(Team team, List<PokemonType> attackTypes) {
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
            result.add(new SwitchIn(member.pokemon(), taken, worst));
        }
        result.sort(Comparator.comparingDouble(SwitchIn::worst)
                .thenComparingDouble(switchIn -> switchIn.taken().values().stream().mapToDouble(Double::doubleValue).sum()));
        return result;
    }

    private Optional<Species.Form> form(PokemonId pokemon) {
        return ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()));
    }
}
