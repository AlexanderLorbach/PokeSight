package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.MoveReader;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.capture.RobotScreenSource;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.settings.SettingsStore;

import java.awt.AWTException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads your Pokémon's moves from the game's move selection for the team editor. The screen area is saved per game.
 * Each read uses its own OCR engine: the battle monitor's engine is busy on its own thread.
 */
final class MoveScan {

    private final SettingsStore store;
    private final String gameId;
    private final Ruleset ruleset;
    private final Language language;

    MoveScan(SettingsStore store, String gameId, Ruleset ruleset, Language language) {
        this.store = Objects.requireNonNull(store);
        this.gameId = Objects.requireNonNull(gameId);
        this.ruleset = Objects.requireNonNull(ruleset);
        this.language = Objects.requireNonNull(language);
    }

    Optional<CaptureRegion> area() {
        return Optional.ofNullable(store.game(gameId).movesArea());
    }

    void setArea(CaptureRegion area) {
        store.saveGame(gameId, store.game(gameId).withMovesArea(area));
    }

    /**
     * Captures the area and reads the damaging moves in it. Blocks for a moment (OCR): call it off the JavaFX thread.
     *
     * @throws IllegalStateException if no area is selected
     */
    List<Integer> read() throws IOException, AWTException {
        CaptureRegion area = area().orElseThrow(() -> new IllegalStateException("No move area selected"));
        BufferedImage image = new RobotScreenSource().capture(area);
        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(language)) {
            return new MoveReader(recognizer, ruleset, language).read(image);
        }
    }
}
