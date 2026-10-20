package de.alorbach.pokesight.game;

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
 * @param withoutAbilities the game has no abilities although its ruleset's generation has (Let's Go and the Legends
 *                     games); matchups then ignore them
 * @param terastal     opponents can terastallize into another type (Scarlet/Violet)
 */
public record GameDefinition(String id, String name, String ruleset, List<Language> languages, String spriteUrl,
                             boolean capitalNames, String region, boolean floatingNames, boolean withoutAbilities,
                             boolean terastal) {

    public GameDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ruleset, "ruleset");
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("At least one language is required");
        }
        languages = List.copyOf(languages);
    }

    GameDefinition withId(String newId) {
        return new GameDefinition(newId, name, ruleset, languages, spriteUrl, capitalNames, region, floatingNames,
                withoutAbilities, terastal);
    }

    @Override
    public String toString() {
        return name;
    }
}
