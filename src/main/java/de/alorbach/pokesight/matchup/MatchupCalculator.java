package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Works out which attack types hurt a defender and which of the team's moves hit it hardest. */
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
        return ruleset.findSpecies(id.speciesId()).map(species -> {
            Species.Form form = species.form(id.form());
            return new EnemyAnalysis(form.name(language), form.types(), defensiveMatchup(form.types()), rankMoves(team, form.types()));
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
     * matches its user (STAB). Stats, level and abilities are ignored; unknown Pokémon or moves are skipped.
     */
    public List<MoveOption> rankMoves(Team team, List<PokemonType> defenderTypes) {
        List<MoveOption> options = new ArrayList<>();
        for (Team.Member member : team.members()) {
            Optional<Species> user = ruleset.findSpecies(member.pokemon().speciesId());
            if (user.isEmpty()) {
                continue;
            }
            List<PokemonType> userTypes = user.get().form(member.pokemon().form()).types();
            for (int moveId : member.moves()) {
                Optional<Move> move = ruleset.findMove(moveId);
                if (move.isEmpty()) {
                    continue;
                }
                double effectiveness = ruleset.typeChart().multiplier(move.get().type(), defenderTypes);
                boolean stab = userTypes.contains(move.get().type());
                double score = move.get().power() * effectiveness * (stab ? STAB : 1);
                options.add(new MoveOption(member.pokemon(), moveId, effectiveness, stab, score));
            }
        }
        options.sort(Comparator.comparingDouble(MoveOption::score).reversed());
        return options;
    }
}
