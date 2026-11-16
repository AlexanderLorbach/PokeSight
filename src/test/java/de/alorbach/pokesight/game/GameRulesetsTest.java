package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import de.alorbach.pokesight.ruleset.RulesetMerge;
import de.alorbach.pokesight.ruleset.Species;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.alorbach.pokesight.ruleset.PokemonType.DRAGON;
import static de.alorbach.pokesight.ruleset.PokemonType.FAIRY;
import static de.alorbach.pokesight.ruleset.PokemonType.FIRE;
import static de.alorbach.pokesight.ruleset.PokemonType.GHOST;
import static de.alorbach.pokesight.ruleset.PokemonType.GRASS;
import static de.alorbach.pokesight.ruleset.PokemonType.NORMAL;
import static de.alorbach.pokesight.ruleset.PokemonType.PSYCHIC;
import static de.alorbach.pokesight.ruleset.PokemonType.STEEL;
import static org.assertj.core.api.Assertions.assertThat;

/** Platinum Together's MODERN POKéMON switch on the real gen4 and gen9 data. */
class GameRulesetsTest {

    private static final String MODERN_POKEMON = "modern-pokemon";
    private static final int BUTTERFREE = 12;
    private static final int CLEFAIRY = 35;
    private static final int RALTS = 280;
    private static final int SYLVEON = 700;
    private static final int SCOVILLAIN = 952;
    private static final int KNOCK_OFF = 282;
    private static final int MOONBLAST = 585;

    private static final GameDefinition TOGETHER = game("platinum-together");
    private static final Map<String, Ruleset> CACHE = new HashMap<>();

    private static GameDefinition game(String id) {
        return GameCatalog.load().stream().filter(game -> game.id().equals(id)).findFirst().orElseThrow();
    }

    private static Ruleset ruleset(GameDefinition game, Map<String, Boolean> options) {
        return GameRulesets.of(game, options, GameRulesetsTest::cached);
    }

    private static Ruleset cached(String id) {
        return CACHE.computeIfAbsent(id, RulesetLoader::load);
    }

    @Test
    void withoutModernPokemonOnlyPlatinumsPokemonTypesAndMoves() {
        Ruleset classic = ruleset(TOGETHER, Map.of(MODERN_POKEMON, false));

        assertThat(classic.typeChart().types()).doesNotContain(FAIRY);
        assertThat(classic.species()).hasSize(493).doesNotContainKey(SCOVILLAIN);
        assertThat(types(classic, RALTS)).containsExactly(PSYCHIC);
        assertThat(types(classic, CLEFAIRY)).containsExactly(NORMAL);
        assertThat(classic.typeChart().multiplier(GHOST, STEEL)).isEqualTo(0.5);
        assertThat(classic.findMove(KNOCK_OFF).orElseThrow().power()).isEqualTo(20);
    }

    @Test
    void modernPokemonBringsTheNewerPokemonTypesAndMoves() {
        Ruleset modern = ruleset(TOGETHER, Map.of(MODERN_POKEMON, true));

        assertThat(types(modern, RALTS)).containsExactly(PSYCHIC, FAIRY); // as on the battle screen
        assertThat(types(modern, CLEFAIRY)).containsExactly(FAIRY);
        assertThat(types(modern, SCOVILLAIN)).containsExactly(GRASS, FIRE);
        assertThat(types(modern, SYLVEON)).containsExactly(FAIRY);
        assertThat(modern.typeChart().multiplier(GHOST, STEEL)).isEqualTo(1.0); // Gen 6+ chart
        assertThat(modern.typeChart().multiplier(DRAGON, FAIRY)).isZero();
        assertThat(modern.findMove(KNOCK_OFF).orElseThrow().power()).isEqualTo(65);
        assertThat(modern.findMove(MOONBLAST).orElseThrow().type()).isEqualTo(FAIRY);
        assertThat(modern.findSpecies(SYLVEON).orElseThrow().name().values()).contains("Sylveon");
    }

    @Test
    void olderPokemonKeepPlatinumsStats() {
        Ruleset modern = ruleset(TOGETHER, Map.of(MODERN_POKEMON, true));

        // Butterfree's Sp. Atk was raised to 90 in Gen 6; no patch note changes Platinum's base stats
        assertThat(modern.findSpecies(BUTTERFREE).orElseThrow().stats().get(Species.Stat.SPECIAL_ATTACK.ordinal())).isEqualTo(80);
    }

    @Test
    void modernPokemonIsOffUntilSwitchedOn() {
        Ruleset defaults = ruleset(TOGETHER, Map.of());

        assertThat(TOGETHER.options()).singleElement().satisfies(option -> assertThat(option.enabledByDefault()).isFalse());
        assertThat(types(defaults, RALTS)).containsExactly(PSYCHIC);
        assertThat(defaults.species()).hasSize(493);
    }

    @Test
    void gamesWithoutOptionsUseTheirRuleset() {
        Ruleset platinum = ruleset(game("platinum"), Map.of(MODERN_POKEMON, true));

        assertThat(platinum).isSameAs(cached("gen4"));
        assertThat(types(platinum, RALTS)).containsExactly(PSYCHIC);
    }

    @Test
    void addedTypesKeepTheBaseChartsOwnPairs() {
        // Newer Pokémon without the newer types: Fairy joins the chart, Platinum's pairs stay
        Ruleset merged = RulesetMerge.withSpeciesFrom(cached("gen4"), cached("gen9"));

        assertThat(merged.typeChart().types()).contains(FAIRY);
        assertThat(merged.typeChart().multiplier(STEEL, FAIRY)).isEqualTo(2.0);
        assertThat(merged.typeChart().multiplier(GHOST, STEEL)).isEqualTo(0.5);
        assertThat(merged.findSpecies(RALTS).orElseThrow().types()).containsExactly(PSYCHIC);
    }

    private static List<PokemonType> types(Ruleset ruleset, int speciesId) {
        return ruleset.findSpecies(speciesId).orElseThrow().types();
    }
}
