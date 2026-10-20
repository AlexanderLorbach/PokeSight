package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.Map;
import java.util.Optional;

/**
 * How an ability changes the damage a Pokémon takes from attack types. Only abilities that change type matchups count:
 * immunities (Levitate), halved or raised damage (Thick Fat, Dry Skin) and Wonder Guard. Effects of the ability pages
 * on Bulbapedia, with the generation each one starts in (Lightning Rod and Storm Drain only grant an immunity since
 * Gen 5).
 *
 * @param factors     multiplier per attack type, applied on top of the type chart
 * @param wonderGuard only super-effective hits do damage
 */
public record AbilityEffect(Map<PokemonType, Double> factors, boolean wonderGuard) {

    private record Rule(int since, AbilityEffect effect) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            immunity("levitate", 3, PokemonType.GROUND),
            immunity("flash-fire", 3, PokemonType.FIRE),
            immunity("water-absorb", 3, PokemonType.WATER),
            immunity("volt-absorb", 3, PokemonType.ELECTRIC),
            immunity("motor-drive", 4, PokemonType.ELECTRIC),
            immunity("lightning-rod", 5, PokemonType.ELECTRIC),
            immunity("storm-drain", 5, PokemonType.WATER),
            immunity("sap-sipper", 5, PokemonType.GRASS),
            immunity("earth-eater", 9, PokemonType.GROUND),
            immunity("well-baked-body", 9, PokemonType.FIRE),
            Map.entry("dry-skin", new Rule(4, new AbilityEffect(Map.of(PokemonType.WATER, 0.0, PokemonType.FIRE, 1.25), false))),
            Map.entry("thick-fat", new Rule(3, new AbilityEffect(Map.of(PokemonType.FIRE, 0.5, PokemonType.ICE, 0.5), false))),
            Map.entry("heatproof", new Rule(4, new AbilityEffect(Map.of(PokemonType.FIRE, 0.5), false))),
            Map.entry("water-bubble", new Rule(7, new AbilityEffect(Map.of(PokemonType.FIRE, 0.5), false))),
            Map.entry("fluffy", new Rule(7, new AbilityEffect(Map.of(PokemonType.FIRE, 2.0), false))),
            Map.entry("purifying-salt", new Rule(9, new AbilityEffect(Map.of(PokemonType.GHOST, 0.5), false))),
            Map.entry("wonder-guard", new Rule(3, new AbilityEffect(Map.of(), true))));

    public AbilityEffect {
        factors = Map.copyOf(factors);
    }

    private static Map.Entry<String, Rule> immunity(String ability, int since, PokemonType type) {
        return Map.entry(ability, new Rule(since, new AbilityEffect(Map.of(type, 0.0), false)));
    }

    /** The effect of the ability in that generation; empty if it does not change type matchups (then). */
    public static Optional<AbilityEffect> of(String ability, int generation) {
        Rule rule = RULES.get(ability);
        return rule != null && generation >= rule.since() ? Optional.of(rule.effect()) : Optional.empty();
    }

    /** The multiplier an attack of that type deals with this ability, given the type chart's multiplier. */
    public double apply(PokemonType attackType, double multiplier) {
        if (wonderGuard) {
            return multiplier > 1 ? multiplier : 0;
        }
        return multiplier * factors.getOrDefault(attackType, 1.0);
    }
}
