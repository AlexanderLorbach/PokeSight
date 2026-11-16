package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import de.alorbach.pokesight.ruleset.RulesetMerge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** The ruleset a game is judged by: its own one as its {@link GameOption options} change it. */
public final class GameRulesets {

    private GameRulesets() {
    }

    /** The game's ruleset with these option values (options not set use their default). */
    public static Ruleset load(GameDefinition game, Map<String, Boolean> options) {
        return of(game, options, RulesetLoader::load);
    }

    /**
     * Takes all enabled options' changes in a fixed order, whatever the order of the options: types, then added
     * species, so Pokémon of newer games come as they are in their own games.
     */
    static Ruleset of(GameDefinition game, Map<String, Boolean> options, Function<String, Ruleset> loader) {
        List<GameOption.Rules> enabled = game.options().stream()
                .filter(option -> isOn(option, options))
                .map(GameOption::rules)
                .toList();
        Ruleset ruleset = loader.apply(game.ruleset());
        for (String source : sources(enabled, GameOption.Rules::typesFrom)) {
            ruleset = RulesetMerge.withTypesFrom(ruleset, loader.apply(source));
        }
        for (String source : sources(enabled, GameOption.Rules::speciesFrom)) {
            ruleset = RulesetMerge.withSpeciesFrom(ruleset, loader.apply(source));
        }
        return ruleset;
    }

    /** Whether the option is on with these saved values. */
    public static boolean isOn(GameOption option, Map<String, Boolean> options) {
        return options.getOrDefault(option.id(), option.enabledByDefault());
    }

    private static Set<String> sources(List<GameOption.Rules> enabled, Function<GameOption.Rules, String> source) {
        Set<String> sources = new LinkedHashSet<>();
        enabled.stream().map(source).filter(Objects::nonNull).forEach(sources::add);
        return sources;
    }
}
