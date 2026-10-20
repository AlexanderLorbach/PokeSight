package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.EnemyAnalysis.AbilityNote;
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
 * its attacks best.
 */
public final class MatchupCalculator {

    private static final double STAB = 1.5;

    private final Ruleset ruleset;
    private final boolean abilities;

    public MatchupCalculator(Ruleset ruleset) {
        this(ruleset, true);
    }

    /** @param abilities the game has abilities (not Let's Go and the Legends games, which share rulesets) */
    public MatchupCalculator(Ruleset ruleset, boolean abilities) {
        this.ruleset = ruleset;
        this.abilities = abilities;
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
            List<AbilityNote> notes = abilityNotes(form, language);
            List<PokemonType> threats = new ArrayList<>(form.types());
            if (tera != null && !threats.contains(tera)) {
                threats.add(tera);
            }
            return new EnemyAnalysis(form.name(language), types, tera != null, defensiveMatchup(types, notes),
                    notes, rankMoves(team, form, types, notes), switchIns(team, threats));
        });
    }

    /** Attack types grouped by the multiplier they deal to these defending types (1x left out). */
    public Matchup defensiveMatchup(List<PokemonType> defenderTypes) {
        return defensiveMatchup(defenderTypes, List.of());
    }

    private Matchup defensiveMatchup(List<PokemonType> defenderTypes, List<AbilityNote> notes) {
        TreeMap<Double, List<PokemonType>> groups = new TreeMap<>();
        for (PokemonType attackType : ruleset.typeChart().types()) {
            double multiplier = withCertainAbility(attackType, ruleset.typeChart().multiplier(attackType, defenderTypes), notes);
            if (multiplier != 1) {
                groups.computeIfAbsent(multiplier, k -> new ArrayList<>()).add(attackType);
            }
        }
        return new Matchup(groups);
    }

    /** Abilities of the form that change type matchups; certain if it is its only ability. */
    private List<AbilityNote> abilityNotes(Species.Form form, Language language) {
        if (!abilities) {
            return List.of();
        }
        boolean only = form.abilities().size() == 1;
        List<AbilityNote> notes = new ArrayList<>();
        for (String ability : form.abilities()) {
            AbilityEffect.of(ability, ruleset.generation())
                    .ifPresent(effect -> notes.add(new AbilityNote(ruleset.abilityName(ability, language), only, effect)));
        }
        return notes;
    }

    private static double withCertainAbility(PokemonType attackType, double multiplier, List<AbilityNote> notes) {
        double result = multiplier;
        for (AbilityNote note : notes) {
            if (note.certain()) {
                result = note.effect().apply(attackType, result);
            }
        }
        return result;
    }

    /**
     * The team's moves against a defender, strongest first: base power x type effectiveness x 1.5 if the move's type
     * matches its user (STAB) x the user's attacking base stat / the defender's defending base stat. Level, natures
     * and stat changes are not known; unknown Pokémon or moves are skipped.
     */
    public List<MoveOption> rankMoves(Team team, Species.Form defender, List<PokemonType> defenderTypes, List<AbilityNote> notes) {
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
                double chart = ruleset.typeChart().multiplier(type, defenderTypes);
                double effectiveness = withCertainAbility(type, chart, notes);
                boolean stab = user.get().types().contains(type);
                boolean physical = move.get().category() == Move.Category.PHYSICAL;
                double statRatio = (double) user.get().stat(physical ? Stat.ATTACK : Stat.SPECIAL_ATTACK)
                        / defender.stat(physical ? Stat.DEFENSE : Stat.SPECIAL_DEFENSE);
                double score = move.get().power() * effectiveness * (stab ? STAB : 1) * statRatio;
                String caveat = notes.stream()
                        .filter(note -> !note.certain() && note.effect().apply(type, effectiveness) != effectiveness)
                        .map(AbilityNote::name)
                        .findFirst()
                        .orElse(null);
                options.add(new MoveOption(member.pokemon(), moveId, effectiveness, stab, score, caveat));
            }
        }
        options.sort(Comparator.comparingDouble(MoveOption::score).reversed());
        return options;
    }

    /**
     * How each team member takes attacks of these types, safest first: by the worst multiplier, then by the sum.
     * A member's own ability counts if it is the species' only one (a Levitate Gengar takes no Ground damage).
     */
    public List<SwitchIn> switchIns(Team team, List<PokemonType> attackTypes) {
        List<SwitchIn> result = new ArrayList<>();
        for (Team.Member member : team.members()) {
            Optional<Species.Form> form = form(member.pokemon());
            if (form.isEmpty()) {
                continue;
            }
            List<AbilityNote> own = abilityNotes(form.get(), Language.EN);
            Map<PokemonType, Double> taken = new LinkedHashMap<>();
            for (PokemonType attackType : attackTypes) {
                taken.put(attackType, withCertainAbility(attackType, ruleset.typeChart().multiplier(attackType, form.get().types()), own));
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
