package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Language;

import java.util.List;

/**
 * What the user picked on the start screen.
 *
 * @param regions     the screen areas to read (single and/or double battle), or the whole screen if none was selected
 * @param description human-readable summary of the regions for the status line
 * @param ownRegion   the area of the player's own name box; null if not selected
 */
public record SessionSettings(GameDefinition game, Language language, List<CaptureRegion> regions, String description,
                              CaptureRegion ownRegion) {

    public SessionSettings {
        regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("At least one capture region is required");
        }
    }
}
