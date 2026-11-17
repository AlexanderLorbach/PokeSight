package de.alorbach.pokesight.game;

import de.alorbach.pokesight.ocr.ImagePreprocessor;
import de.alorbach.pokesight.ocr.ImagePreprocessor.TextColor;
import de.alorbach.pokesight.ruleset.Language;

import java.util.List;
import java.util.Objects;

/**
 * A playable game as described by {@code games/<id>.yaml}.
 *
 * @param spriteUrl    where to download the game's Pokémon sprites, {@code {name}} = sprite name (see
 *                     {@link de.alorbach.pokesight.sprite.SpriteCache}); null if there are none
 * @param capitalNames the battle screen shows Pokémon names in capitals (Gen 1-4); OCR text in mixed case is then
 *                     ignored, which keeps names in other windows (code, chats) from being taken for a battle
 * @param region       the game's region if it has regional forms (Sun/Moon: "alola"); a recognized species is then
 *                     shown in its regional form. Null for games without them
 * @param floatingNames the opponent's name floats above it in the 3D scene (Legends: Arceus, Scarlet/Violet,
 *                     Legends: Z-A) instead of standing in a fixed name box
 * @param yellowNames  some battle names are yellow instead of white (Platinum Together: the player's Pokémon whose
 *                     turn it is); light text is then read in both colours (see {@link #nameColors})
 * @param terastal     opponents can terastallize into another type (Scarlet/Violet)
 * @param spriteSheets the sprite files are hg-engine sprite sheets (frames side by side, palette colour 0 is the
 *                     background); only the first frame is kept, with a transparent background
 * @param options      switches of the game that change its rules, shown on the start screen (Platinum Together's
 *                     MODERN POKéMON); empty for games whose rules are fixed
 * @param tierList     the Smogon in-game tier list that decides the catch star ({@code tiers/<id>.yaml}, see
 *                     {@link de.alorbach.pokesight.catching.CatchAdvice}); null if there is none
 * @param category     where the game is listed (official, ROM hack, custom). Set by {@link GameCatalog}
 */
public record GameDefinition(String id, String name, String ruleset, List<Language> languages, String spriteUrl,
                             boolean capitalNames, String region, boolean floatingNames, boolean yellowNames,
                             boolean terastal, boolean spriteSheets, List<GameOption> options, String tierList,
                             GameCategory category) {

    public GameDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ruleset, "ruleset");
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("At least one language is required");
        }
        languages = List.copyOf(languages);
        options = options == null ? List.of() : List.copyOf(options);
        if (options.stream().map(GameOption::id).distinct().count() != options.size()) {
            throw new IllegalArgumentException("Game option ids must be unique: " + options);
        }
        category = category == null ? GameCategory.OFFICIAL : category;
    }

    GameDefinition withId(String newId, GameCategory newCategory) {
        return new GameDefinition(newId, name, ruleset, languages, spriteUrl, capitalNames, region, floatingNames,
                yellowNames, terastal, spriteSheets, options, tierList, newCategory);
    }

    /** The text colours of the battle screen's names, for {@link de.alorbach.pokesight.battle.BattleScanner}. */
    public List<TextColor> nameColors() {
        return yellowNames ? List.of(TextColor.DARK, TextColor.LIGHT_OR_YELLOW) : ImagePreprocessor.DARK_AND_LIGHT;
    }

    @Override
    public String toString() {
        return name;
    }
}
