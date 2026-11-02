package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameCatalogTest {

    private static final String PINNED_SPRITE_COMMIT = "7a7d66b1620e646f69afd48dc1273da9033ede9c";

    @Test
    void loadsAGameDefinition() {
        GameDefinition platinum = GameCatalog.load().stream()
                .filter(game -> game.id().equals("platinum"))
                .findFirst()
                .orElseThrow();

        assertThat(platinum.name()).isEqualTo("Pokémon Platinum");
        assertThat(platinum.ruleset()).isEqualTo("gen4");
        assertThat(platinum.languages()).containsExactly(Language.EN, Language.DE);
        assertThat(platinum.spriteUrl())
                .startsWith("https://raw.githubusercontent.com/PokeAPI/sprites/")
                .endsWith("/generation-iv/platinum/{name}.png");
        assertThat(platinum.capitalNames()).isTrue();
    }

    @Test
    void everyGameHasAnExistingRulesetAndPinnedSprites() {
        List<GameDefinition> games = GameCatalog.load();

        assertThat(games).extracting(GameDefinition::id).doesNotHaveDuplicates();
        assertThat(games).allSatisfy(game -> {
            assertThat(RulesetLoader.load(game.ruleset()).species()).isNotEmpty();
            assertThat(game.languages()).contains(Language.EN);
            assertThat(game.spriteUrl()).contains("/" + PINNED_SPRITE_COMMIT + "/").endsWith("{name}.png");
        });
        assertThat(games).filteredOn(game -> !game.romHack()).hasSize(22)
                .allSatisfy(game -> assertThat(game.languages()).containsExactly(Language.EN, Language.DE));
    }

    @Test
    void listsRomHacksBelowTheOfficialGames() {
        List<GameDefinition> games = GameCatalog.load();
        GameDefinition heartfeltGold = game("heartfelt-gold");

        assertThat(games.getLast()).isEqualTo(heartfeltGold);
        assertThat(games.stream().dropWhile(game -> !game.romHack())).allSatisfy(game -> assertThat(game.romHack()).isTrue());
        assertThat(heartfeltGold.romHack()).isTrue();
        assertThat(heartfeltGold.ruleset()).isEqualTo("heartfelt-gold");
        assertThat(heartfeltGold.languages()).containsExactly(Language.EN); // hg-engine works on the US ROM only
        assertThat(heartfeltGold.capitalNames()).isFalse();
        assertThat(game("heartgold-soulsilver").romHack()).isFalse();
    }

    @Test
    void knowsGamesWithoutAbilitiesAndWithTerastallization() {
        assertThat(game("lets-go").withoutAbilities()).isTrue();
        assertThat(game("legends-arceus").withoutAbilities()).isTrue();
        assertThat(game("legends-za").withoutAbilities()).isTrue();
        assertThat(game("sword-shield").withoutAbilities()).isFalse();
        assertThat(game("scarlet-violet").terastal()).isTrue();
        assertThat(game("sword-shield").terastal()).isFalse();
    }

    @Test
    void writesNamesInCapitalsUntilGen4() {
        assertThat(game("red-blue").capitalNames()).isTrue();
        assertThat(game("heartgold-soulsilver").capitalNames()).isTrue();
        assertThat(game("black-white").capitalNames()).isFalse();
        assertThat(game("scarlet-violet").capitalNames()).isFalse();
    }

    @Test
    void knowsTheRegionOfGamesWithRegionalForms() {
        assertThat(game("sun-moon").region()).isEqualTo("alola");
        assertThat(game("sword-shield").region()).isEqualTo("galar");
        assertThat(game("legends-arceus").region()).isEqualTo("hisui");
        assertThat(game("scarlet-violet").region()).isEqualTo("paldea");
        assertThat(game("platinum").region()).isNull();
    }

    private static GameDefinition game(String id) {
        return GameCatalog.load().stream().filter(game -> game.id().equals(id)).findFirst().orElseThrow();
    }
}
