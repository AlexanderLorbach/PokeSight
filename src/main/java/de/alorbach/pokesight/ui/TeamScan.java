package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.BattleScanner;
import de.alorbach.pokesight.battle.MoveReader;
import de.alorbach.pokesight.battle.NameMatcher;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.battle.Sighting;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.capture.RobotScreenSource;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.settings.SettingsStore;

import java.awt.AWTException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads one of your Pokémon from the game for the team editor: its name and its moves, from two screen areas that are
 * saved per game (the move selection or the summary page shows both). Each read uses its own OCR engine: the battle
 * monitor's engine is busy on its own thread.
 */
final class TeamScan {

    /**
     * What one read found.
     *
     * @param pokemon the Pokémon whose name was read; empty without a name area or if no name was recognized
     * @param moves   the damaging moves read, in reading order
     */
    record Reading(Optional<PokemonId> pokemon, List<Integer> moves) {
        Reading {
            moves = List.copyOf(moves);
        }
    }

    private final SettingsStore store;
    private final GameDefinition game;
    private final Ruleset ruleset;
    private final Language language;
    /** Kept here too: if saving fails, the areas still work until PokeSight is closed. */
    private CaptureRegion movesArea;
    private CaptureRegion nameArea;

    TeamScan(SettingsStore store, GameDefinition game, Ruleset ruleset, Language language) {
        this.store = Objects.requireNonNull(store);
        this.game = Objects.requireNonNull(game);
        this.ruleset = Objects.requireNonNull(ruleset);
        this.language = Objects.requireNonNull(language);
        this.movesArea = store.game(game.id()).movesArea();
        this.nameArea = store.game(game.id()).nameArea();
    }

    Optional<CaptureRegion> movesArea() {
        return Optional.ofNullable(movesArea);
    }

    Optional<CaptureRegion> nameArea() {
        return Optional.ofNullable(nameArea);
    }

    /** @throws java.io.UncheckedIOException if the area cannot be saved; it is used anyway */
    void setMovesArea(CaptureRegion newArea) {
        movesArea = newArea;
        store.saveGame(game.id(), store.game(game.id()).withMovesArea(newArea));
    }

    /** @throws java.io.UncheckedIOException if the area cannot be saved; it is used anyway */
    void setNameArea(CaptureRegion newArea) {
        nameArea = newArea;
        store.saveGame(game.id(), store.game(game.id()).withNameArea(newArea));
    }

    /**
     * Captures the selected areas and reads the Pokémon's name and its damaging moves. Blocks for a moment (OCR): call
     * it off the JavaFX thread.
     *
     * @throws IllegalStateException if no moves area is selected
     */
    Reading read() throws IOException, AWTException {
        CaptureRegion moves = movesArea().orElseThrow(() -> new IllegalStateException("No moves area selected"));
        RobotScreenSource screen = new RobotScreenSource();
        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(language)) {
            Optional<PokemonId> pokemon = Optional.empty();
            if (nameArea != null) {
                // The summary's name box is a fixed box in every game, also in those whose battle names float.
                BattleScanner names = new BattleScanner(recognizer, new NameMatcher(ruleset, language, game.region()),
                        game.capitalNames());
                pokemon = names.scan(screen.capture(nameArea)).stream().findFirst().map(Sighting::pokemon);
            }
            return new Reading(pokemon, new MoveReader(recognizer, ruleset, language).read(screen.capture(moves)));
        }
    }
}
