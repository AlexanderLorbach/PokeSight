package de.alorbach.pokesight.game;

import de.alorbach.pokesight.matchup.MovePool;
import de.alorbach.pokesight.ruleset.Learnsets;
import de.alorbach.pokesight.ruleset.Ruleset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Which moves a game's Pokémon learn: its own learnsets, as its {@link GameOption options} extend them. */
public final class GameLearnsets {

    /**
     * Learnsets of newer games, newest first, for the Pokémon an option brings from them (Platinum Together's MODERN
     * POKéMON): each Pokémon learns what it learns in the newest of these games that has it.
     */
    static final List<String> NEWER_GAMES = List.of("scarlet-violet", "sword-shield", "legends-arceus",
            "ultra-sun-ultra-moon", "omega-ruby-alpha-sapphire", "black-2-white-2");

    private GameLearnsets() {
    }

    /**
     * @param ruleset the game's ruleset with these options (see {@link GameRulesets})
     * @return empty for games without learnsets (ROM hacks)
     */
    public static Optional<MovePool> load(GameDefinition game, Map<String, Boolean> options, Ruleset ruleset) {
        return of(game, options, ruleset, Learnsets::load);
    }

    static Optional<MovePool> of(GameDefinition game, Map<String, Boolean> options, Ruleset ruleset,
                                 Function<String, Learnsets> loader) {
        if (game.learnsets() == null) {
            return Optional.empty();
        }
        List<Learnsets> sources = new ArrayList<>(List.of(loader.apply(game.learnsets())));
        boolean addsSpecies = game.options().stream()
                .anyMatch(option -> GameRulesets.isOn(option, options) && option.rules().speciesFrom() != null);
        if (addsSpecies) {
            NEWER_GAMES.stream().map(loader).forEach(sources::add);
        }
        return Optional.of(new MovePool(ruleset, sources));
    }
}
