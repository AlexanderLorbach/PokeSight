package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ruleset.Language;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameCatalogTest {

    @Test
    void loadsPlatinum() {
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
}
