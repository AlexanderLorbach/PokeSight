package de.alorbach.pokesight.catching;

import de.alorbach.pokesight.game.GameCatalog;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.game.GameRulesets;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The catch star on the real data: Smogon's lists, PokeAPI's evolutions and the rulesets. */
class CatchAdviceTest {

    private static final Pokedex POKEDEX = Pokedex.load();
    private static final List<GameDefinition> GAMES = GameCatalog.load();

    private static final int SQUIRTLE = 7;
    private static final int RATTATA = 19;
    private static final int ABRA = 63;
    private static final int KADABRA = 64;
    private static final int ALAKAZAM = 65;
    private static final int GASTLY = 92;
    private static final int MAGIKARP = 129;
    private static final int GYARADOS = 130;
    private static final int EEVEE = 133;
    private static final int VAPOREON = 134;
    private static final int FLAREON = 136;
    private static final int TEDDIURSA = 216;
    private static final int LUGIA = 249;
    private static final int CELEBI = 251;
    private static final int BAGON = 371;
    private static final int GIBLE = 443;
    private static final int GARCHOMP = 445;
    private static final int DEINO = 633;
    private static final int NIHILEGO = 793;
    private static final int GREAT_TUSK = 984;
    private static final int GLAZED_MEGA_VENUSAUR = 5269;

    private static GameDefinition game(String id) {
        return GAMES.stream().filter(game -> game.id().equals(id)).findFirst().orElseThrow();
    }

    private static CatchAdvice advice(String gameId) {
        GameDefinition game = game(gameId);
        return CatchAdvice.forGame(game, RulesetLoader.load(game.ruleset()), Language.EN);
    }

    @Test
    void starsTheStarredTiersOfTheGamesList() {
        CatchAdvice hgss = advice("heartgold-soulsilver");

        assertThat(hgss.reason(ABRA)).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("Smogon in-game tier S: Abra (Trade)")
                .contains("HeartGold and SoulSilver"));
        assertThat(hgss.reason(GASTLY)).hasValueSatisfying(reason -> assertThat(reason).startsWith("Smogon in-game tier A: Gastly (Trade)"));
        assertThat(hgss.reason(RATTATA)).isEmpty(); // B tier
    }

    @Test
    void earlierStagesShareTheStarOfTheirEvolution() {
        CatchAdvice hgss = advice("heartgold-soulsilver");

        assertThat(hgss.reason(MAGIKARP)).hasValueSatisfying(reason -> assertThat(reason).contains("tier S: Gyarados"));
        assertThat(hgss.reason(GYARADOS)).isPresent();
        assertThat(hgss.reason(KADABRA)).isPresent();
        assertThat(hgss.reason(ALAKAZAM)).isPresent();
    }

    @Test
    void anEntryForOneEvolutionPathCoversOnlyThatPath() {
        CatchAdvice frlg = advice("firered-leafgreen"); // "Eevee (Vaporeon)" is A, "Eevee (Flareon)" C

        assertThat(frlg.reason(EEVEE)).hasValueSatisfying(reason -> assertThat(reason).contains("Eevee (Vaporeon)"));
        assertThat(frlg.reason(VAPOREON)).isPresent();
        assertThat(frlg.reason(FLAREON)).isEmpty();
    }

    @Test
    void entryCoversEarlierStagesAndLaterOnesUpToItsTarget() {
        TierList.Entry noTrade = new TierList.Entry("B", "Abra (No Trade)", ABRA, KADABRA, null);
        TierList.Entry wholeLine = new TierList.Entry("S", "Gyarados", GYARADOS, null, null);
        TierList.Entry kadabra = new TierList.Entry("A", "Kadabra", KADABRA, null, null);

        assertThat(noTrade.covers(ABRA, POKEDEX)).isTrue();
        assertThat(noTrade.covers(KADABRA, POKEDEX)).isTrue();
        assertThat(noTrade.covers(ALAKAZAM, POKEDEX)).isFalse();
        assertThat(wholeLine.covers(MAGIKARP, POKEDEX)).isTrue();
        assertThat(kadabra.covers(ABRA, POKEDEX)).isTrue();
        assertThat(kadabra.covers(ALAKAZAM, POKEDEX)).isTrue();
        assertThat(kadabra.covers(GASTLY, POKEDEX)).isFalse();
    }

    @Test
    void versionExclusiveEntriesOnlyCountInTheirGame() {
        assertThat(advice("red-blue").reason(SQUIRTLE)).hasValueSatisfying(reason -> assertThat(reason).contains("Squirtle (RB)"));
        assertThat(advice("yellow").reason(SQUIRTLE)).isEmpty(); // "Squirtle (Y)" is B
        assertThat(advice("crystal").reason(TEDDIURSA)).isPresent();
        assertThat(advice("gold-silver").reason(TEDDIURSA)).isEmpty();
    }

    @Test
    void legendaryAndMythicalPokemonAlwaysGetTheStar() {
        CatchAdvice hgss = advice("heartgold-soulsilver");

        assertThat(hgss.reason(LUGIA)).contains("Legendary Pokémon");
        assertThat(hgss.reason(CELEBI)).contains("Mythical Pokémon"); // untiered in the list
        assertThat(advice("sun-moon").reason(NIHILEGO)).contains("Ultra Beast");
        assertThat(advice("scarlet-violet").reason(GREAT_TUSK)).contains("Paradox Pokémon");
    }

    @Test
    void pokemonTheListLeavesOutGetNoStar() {
        assertThat(advice("heartgold-soulsilver").reason(BAGON)).isEmpty(); // Hoenn Pokémon, rated by no HGSS tier
    }

    @Test
    void gamesWithoutListStarTheStrongestOfEachTypeAndTheirEarlierStages() {
        CatchAdvice swordShield = advice("sword-shield");

        assertThat(swordShield.reason(GARCHOMP)).hasValueSatisfying(reason -> assertThat(reason)
                .isEqualTo("Top " + CatchAdvice.TOP_PER_TYPE + " Dragon/Ground Pokémon by base stats: Garchomp (600)"));
        assertThat(swordShield.reason(GIBLE)).isEqualTo(swordShield.reason(GARCHOMP));
        assertThat(swordShield.reason(RATTATA)).isEmpty();
    }

    @Test
    void baseStatsDecideOnlyForPokemonNewerThanTheList() {
        GameDefinition together = game("platinum-together");
        Ruleset modern = GameRulesets.load(together, Map.of("modern-pokemon", true));
        CatchAdvice advice = CatchAdvice.forGame(together, modern, Language.EN);

        assertThat(advice.reason(DEINO)).hasValueSatisfying(reason -> assertThat(reason).contains("Hydreigon (600)"));
        assertThat(advice.reason(GIBLE)).hasValueSatisfying(reason -> assertThat(reason).contains("Platinum In-Game Tiers"));
        assertThat(advice.reason(EEVEE)).isEmpty(); // Sylveon's stats do not overrule the Platinum list
    }

    @Test
    void speciesARomHackAddedGetNoStar() {
        CatchAdvice glazed = advice("glazed");

        assertThat(glazed.reason(GLAZED_MEGA_VENUSAUR)).isEmpty();
    }

    @Test
    void everyTierListLoadsAndNamesKnownPokemonAndGames() {
        Set<String> gameIds = GAMES.stream().map(GameDefinition::id).collect(Collectors.toSet());
        List<GameDefinition> withList = GAMES.stream().filter(game -> game.tierList() != null).toList();

        assertThat(withList).hasSizeGreaterThanOrEqualTo(17);
        for (GameDefinition game : withList) {
            TierList list = TierList.load(game.tierList());
            assertThat(list.entries()).as(game.tierList()).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.species()).isBetween(1, list.lastDex());
                if (entry.upTo() != null) {
                    assertThat(POKEDEX.isAncestorOrEqual(entry.species(), entry.upTo())).as(entry.name()).isTrue();
                }
                if (entry.games() != null) {
                    assertThat(gameIds).as(entry.name()).containsAll(entry.games());
                }
            });
            assertThat(list.entries()).extracting(TierList.Entry::tier).as(game.tierList()).containsAll(list.starred());
        }
    }
}
