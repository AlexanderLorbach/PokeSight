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
 */
public record GameDefinition(String id, String name, String ruleset, List<Language> languages, String spriteUrl,
                             boolean capitalNames) {

    public GameDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ruleset, "ruleset");
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("At least one language is required");
        }
        languages = List.copyOf(languages);
    }

    GameDefinition withId(String newId) {
        return new GameDefinition(newId, name, ruleset, languages, spriteUrl, capitalNames);
    }

    @Override
    public String toString() {
        return name;
    }
}
