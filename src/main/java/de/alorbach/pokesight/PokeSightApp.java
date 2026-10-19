package de.alorbach.pokesight;

import de.alorbach.pokesight.battle.BattleMonitor;
import de.alorbach.pokesight.battle.BattleScanner;
import de.alorbach.pokesight.battle.NameMatcher;
import de.alorbach.pokesight.capture.RobotScreenSource;
import de.alorbach.pokesight.game.GameCatalog;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.matchup.MatchupCalculator;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ocr.TextRecognizer;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import de.alorbach.pokesight.settings.SettingsStore;
import de.alorbach.pokesight.ui.BattleView;
import de.alorbach.pokesight.ui.SessionSettings;
import de.alorbach.pokesight.ui.StartView;
import de.alorbach.pokesight.ui.Theme;
import de.alorbach.pokesight.ui.WindowState;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Entry point: start screen (game, language, screen areas) -> battle view. Wires all components. */
public final class PokeSightApp extends Application {

    private static final Duration SCAN_INTERVAL = Duration.ofMillis(700);
    private static final System.Logger LOG = System.getLogger(PokeSightApp.class.getName());

    private Stage stage;
    private SettingsStore store;
    private Session session;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.store = SettingsStore.openDefault();
        Scene scene = new Scene(new Pane(), 1000, 680);
        Theme.apply(scene);
        stage.setTitle("PokeSight");
        Theme.decorate(stage);
        stage.setScene(scene);
        WindowState.restore(stage, store);
        showStartView();
        stage.show();
    }

    private void showStartView() {
        closeSession();
        List<GameDefinition> games;
        try {
            games = GameCatalog.load();
        } catch (RuntimeException e) {
            showError("Cannot load the game list", e);
            Platform.exit();
            return;
        }
        stage.getScene().setRoot(new StartView(games, store, this::startSession));
    }

    private void startSession(SessionSettings settings) {
        TextRecognizer recognizer = null;
        try {
            Ruleset ruleset = RulesetLoader.load(settings.game().ruleset());
            recognizer = new TesseractTextRecognizer(settings.language());
            BattleScanner scanner = new BattleScanner(recognizer, new NameMatcher(ruleset, settings.language()),
                    settings.game().capitalNames());
            BattleView view = new BattleView(settings, new MatchupCalculator(ruleset), store, this::showStartView);
            BattleMonitor monitor = new BattleMonitor(new RobotScreenSource(), settings.regions(), scanner,
                    update -> Platform.runLater(() -> view.show(update)));
            view.setOnScanNow(monitor::scanNow);
            view.setOnReset(monitor::reset);
            view.setCaptureSupplier(monitor::lastCaptures);

            session = new Session(monitor, recognizer);
            stage.getScene().setRoot(view);
            monitor.start(SCAN_INTERVAL);
        } catch (IOException | AWTException | RuntimeException e) {
            if (recognizer != null) {
                recognizer.close();
            }
            showError("Cannot start " + settings.game().name(), e);
        }
    }

    @Override
    public void stop() {
        try {
            WindowState.save(stage, store);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save the window position", e); // never block closing
        }
        closeSession();
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void showError(String title, Exception e) {
        Theme.showError(stage, title, e.getMessage());
    }

    /** Resources that live as long as one game is being watched. The monitor stops before the OCR engine closes. */
    private record Session(BattleMonitor monitor, TextRecognizer recognizer) implements AutoCloseable {
        @Override
        public void close() {
            monitor.close();
            recognizer.close();
        }
    }
}
