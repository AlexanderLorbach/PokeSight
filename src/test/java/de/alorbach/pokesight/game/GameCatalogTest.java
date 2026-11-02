package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
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
            assertThat(game.spriteUrl()).containsPattern("/[0-9a-f]{40}/").contains("{name}").endsWith(".png"); // pinned commit
        });
        assertThat(games).filteredOn(game -> !game.romHack()).hasSize(22).allSatisfy(game -> {
            assertThat(game.languages()).containsExactly(Language.EN, Language.DE);
            assertThat(game.spriteUrl()).contains("/" + PINNED_SPRITE_COMMIT + "/").endsWith("{name}.png");
            assertThat(game.spriteSheets()).isFalse();
        });
        // ROM hacks show their own sprites where they publish them (Heartfelt Gold: hg-engine's)
        assertThat(game("heartfelt-gold").spriteUrl()).contains("/BluRosie/hg-engine/");
        assertThat(game("heartfelt-gold").spriteSheets()).isTrue();
        assertThat(game("rowe").spriteUrl()).contains("/BelialClover/RoweRepo/");
        assertThat(game("rowe").spriteSheets()).isTrue();
        assertThat(game("unbound").spriteUrl()).contains("/Skeli789/Dynamic-Pokemon-Expansion/");
        assertThat(game("unbound").spriteSheets()).isTrue();
    }

    @Test
    void listsRomHacksBelowTheOfficialGames() {
        List<GameDefinition> games = GameCatalog.load();
        GameDefinition heartfeltGold = game("heartfelt-gold");
        GameDefinition odyssey = game("odyssey");
        GameDefinition rowe = game("rowe");
        GameDefinition unbound = game("unbound");

        assertThat(games.stream().dropWhile(game -> !game.romHack())).containsExactly(heartfeltGold, odyssey, rowe, unbound);
        assertThat(heartfeltGold.ruleset()).isEqualTo("heartfelt-gold");
        assertThat(heartfeltGold.languages()).containsExactly(Language.EN); // hg-engine works on the US ROM only
        assertThat(heartfeltGold.capitalNames()).isFalse();
        assertThat(odyssey.ruleset()).isEqualTo("odyssey");
        assertThat(odyssey.languages()).containsExactly(Language.EN);
        assertThat(odyssey.capitalNames()).isFalse();
        assertThat(rowe.ruleset()).isEqualTo("rowe");
        assertThat(rowe.languages()).containsExactly(Language.EN);
        assertThat(rowe.capitalNames()).isFalse();
        assertThat(unbound.ruleset()).isEqualTo("unbound");
        assertThat(unbound.languages()).containsExactly(Language.EN);
        assertThat(unbound.capitalNames()).isFalse();
        assertThat(game("heartgold-soulsilver").romHack()).isFalse();
        assertThat(game("emerald").romHack()).isFalse();
    }

    @Test
    void romHacksLeaveTheOfficialGamesUnchanged() {
        List<GameDefinition> games = GameCatalog.load();
        List<GameDefinition> official = games.stream().filter(game -> !game.romHack()).toList();
        List<String> hackRulesets = games.stream().filter(GameDefinition::romHack).map(GameDefinition::ruleset).toList();

        assertThat(games.subList(0, official.size())).isEqualTo(official); // official games first, order kept
        assertThat(official).allSatisfy(game -> {
            assertThat(hackRulesets).doesNotContain(game.ruleset());
            Ruleset ruleset = RulesetLoader.load(game.ruleset());
            assertThat(ruleset.typeChart().types()).doesNotContain(PokemonType.AETHER); // hacks' own types
            assertThat(ruleset.species().values()).allSatisfy(species -> assertThat(species.sprite()).isNull());
        });
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
