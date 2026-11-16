package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Language;

import java.util.List;
import java.util.Map;

/**
 * What the user picked on the start screen.
 *
 * @param regions     the screen areas to read (single and/or double battle), or the whole screen if none was selected
 * @param description human-readable summary of the regions for the status line
 * @param ownRegions  the areas of the player's own name boxes (single and/or double battle); empty if none selected
 * @param options     the game's option values (Platinum Together's MODERN POKéMON); options not set use their default
 */
public record SessionSettings(GameDefinition game, Language language, List<CaptureRegion> regions, String description,
                              List<CaptureRegion> ownRegions, Map<String, Boolean> options) {

    public SessionSettings {
        regions = List.copyOf(regions);
        ownRegions = ownRegions == null ? List.of() : List.copyOf(ownRegions);
        options = options == null ? Map.of() : Map.copyOf(options);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("At least one capture region is required");
        }
    }
}
