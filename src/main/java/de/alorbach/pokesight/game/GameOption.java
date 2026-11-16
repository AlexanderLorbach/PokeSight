package de.alorbach.pokesight.game;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A switch of the game that changes its rules, chosen on the start screen (Platinum Together: "MODERN POKéMON"), and
 * how it changes the ruleset PokeSight judges matchups with. See {@link GameRulesets}.
 *
 * @param id               key in the app settings; lower case letters, digits and "-"
 * @param label            the game's own name of the setting
 * @param description      what it changes, shown as a tooltip
 * @param enabledByDefault the value until the player sets it
 * @param rules            what changes when it is on
 */
public record GameOption(String id, String label, String description, boolean enabledByDefault, Rules rules) {

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    public GameOption {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a game option id: " + id);
        }
        Objects.requireNonNull(label, "label");
        description = description == null ? "" : description;
        rules = rules == null ? Rules.NONE : rules;
    }

    /**
     * Changes to the game's ruleset while the option is on: types first, then added species.
     *
     * @param typesFrom   ruleset whose type chart, species types and move data replace the game's (the newer games'
     *                    types: Fairy, Gen 6+ chart). Species, stats and forms stay the game's own
     * @param speciesFrom ruleset whose species and moves that the game lacks are added (Pokémon of newer games)
     */
    public record Rules(String typesFrom, String speciesFrom) {
        public static final Rules NONE = new Rules(null, null);
    }
}
