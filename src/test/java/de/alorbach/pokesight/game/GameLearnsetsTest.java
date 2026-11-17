package de.alorbach.pokesight.game;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.MovePool;
import de.alorbach.pokesight.ruleset.Ruleset;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Platinum Together's learnsets: Platinum's own, and with MODERN POKéMON the newer games' for their Pokémon. */
class GameLearnsetsTest {

    private static final GameDefinition TOGETHER = GameCatalog.load().stream()
            .filter(game -> game.id().equals("platinum-together")).findFirst().orElseThrow();
    private static final PokemonId INFERNAPE = PokemonId.of(392);
    private static final PokemonId FLORAGATO = PokemonId.of(907);
    private static final PokemonId DEINO = PokemonId.of(633);
    private static final int FLAMETHROWER = 53;

    @Test
    void platinumsOwnPokemonLearnWhatTheyLearnInPlatinum() {
        MovePool pool = pool(false);

        assertThat(pool.of(INFERNAPE)).extracting(MovePool.Learnable::source).containsOnly("platinum");
        assertThat(pool.machine(FLAMETHROWER)).contains("TM35");
        assertThat(pool.knows(FLORAGATO)).isFalse();
    }

    @Test
    void modernPokemonLearnWhatTheyLearnInTheNewestGameThatHasThem() {
        MovePool pool = pool(true);

        assertThat(pool.of(INFERNAPE)).extracting(MovePool.Learnable::source).containsOnly("platinum");
        assertThat(pool.of(FLORAGATO)).isNotEmpty().extracting(MovePool.Learnable::source).containsOnly("scarlet-violet");
        assertThat(pool.of(DEINO)).isNotEmpty();
    }

    @Test
    void romHacksHaveNoLearnsets() {
        GameDefinition unbound = GameCatalog.load().stream().filter(game -> game.id().equals("unbound")).findFirst().orElseThrow();

        assertThat(GameLearnsets.load(unbound, Map.of(), GameRulesets.load(unbound, Map.of()))).isEmpty();
    }

    private static MovePool pool(boolean modern) {
        Map<String, Boolean> options = Map.of("modern-pokemon", modern);
        Ruleset ruleset = GameRulesets.load(TOGETHER, options);
        return GameLearnsets.load(TOGETHER, options, ruleset).orElseThrow();
    }
}
