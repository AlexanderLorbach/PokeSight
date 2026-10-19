package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.settings.AppSettings;
import de.alorbach.pokesight.settings.GameSettings;
import de.alorbach.pokesight.settings.SettingsStore;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Start screen: pick the game (= its YAML file), the language and, optionally, where the opponents'
 * names appear in single and double battles. Without a selected area the whole main screen is read.
 * Choices are remembered in the settings files.
 */
public final class StartView extends BorderPane {

    private final SettingsStore store;
    private final ComboBox<GameDefinition> game = new ComboBox<>();
    private final ComboBox<Language> language = new ComboBox<>();
    private final AreaRow singleArea = new AreaRow(true, "Drag a rectangle around the opponent's name box in a single battle");
    private final AreaRow doubleArea = new AreaRow(false, "Drag a rectangle around both opponents' name boxes in a double battle");

    public StartView(List<GameDefinition> games, SettingsStore store, Consumer<SessionSettings> onStart) {
        this.store = store;
        AppSettings saved = store.app();
        language.setConverter(new StringConverter<>() {
            @Override
            public String toString(Language value) {
                return value == null ? "" : value.displayName();
            }

            @Override
            public Language fromString(String text) {
                throw new UnsupportedOperationException("Language combo box is not editable");
            }
        });
        game.getItems().setAll(games);
        game.valueProperty().addListener((obs, old, selected) -> gameChanged(selected, saved.language()));
        game.setValue(games.stream()
                .filter(g -> g.id().equals(saved.game()))
                .findFirst()
                .orElse(games.isEmpty() ? null : games.getFirst()));

        GridPane form = new GridPane();
        form.getStyleClass().add("form");
        form.addRow(0, formLabel("Game"), game);
        form.addRow(1, formLabel("Language"), language);
        form.addRow(2, formLabel("Single battle area"), singleArea);
        form.addRow(3, formLabel("Double battle area"), doubleArea);

        Label hint = new Label("""
                Areas are optional: drag a rectangle around the opponents' name boxes. Each scan reads every selected \
                area and uses the one with the most names. Without an area the whole main screen is read, which is \
                slower and may pick up other windows. Never let PokeSight cover a selected area.""");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        Button start = new Button("Start");
        start.getStyleClass().addAll("primary", "start-button");
        start.setDefaultButton(true);
        start.setOnAction(event -> submit(onStart));

        Label title = new Label("PokeSight");
        title.getStyleClass().add("title");
        Label subtitle = new Label("Live weaknesses of your opponents, right next to your game.");
        subtitle.getStyleClass().add("subtitle");
        HBox heading = new HBox(16, PokeBall.of(56), new VBox(2, title, subtitle));
        heading.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(heading, form, hint, start);
        card.getStyleClass().add("start-card");
        VBox content = new VBox(card);
        content.getStyleClass().add("start-view");
        content.setAlignment(Pos.CENTER);
        setTop(TitleBar.of("PokeSight", null));
        setCenter(content);
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    private void gameChanged(GameDefinition selected, Language savedLanguage) {
        if (selected == null) {
            language.getItems().clear();
            return;
        }
        List<Language> available = selected.languages().stream().sorted().toList();
        language.getItems().setAll(available);
        language.setValue(available.contains(savedLanguage) ? savedLanguage : available.getFirst());
        GameSettings settings = store.game(selected.id());
        singleArea.load(selected.id(), settings.singleArea());
        doubleArea.load(selected.id(), settings.doubleArea());
    }

    private void submit(Consumer<SessionSettings> onStart) {
        GameDefinition selectedGame = game.getValue();
        if (selectedGame == null || language.getValue() == null) {
            return; // the combo boxes always have a value as long as there is a game
        }
        store.saveApp(store.app().withGame(selectedGame.id(), language.getValue()));

        List<CaptureRegion> regions = new ArrayList<>();
        singleArea.region().ifPresent(regions::add);
        doubleArea.region().ifPresent(regions::add);
        String description = switch (regions.size()) {
            case 0 -> "whole screen";
            case 1 -> singleArea.region().isPresent() ? "single battle area" : "double battle area";
            default -> "single + double battle areas";
        };
        if (regions.isEmpty()) {
            Rectangle2D screen = Screen.getPrimary().getBounds();
            regions.add(new CaptureRegion((int) screen.getMinX(), (int) screen.getMinY(), (int) screen.getWidth(), (int) screen.getHeight()));
        }
        onStart.accept(new SessionSettings(selectedGame, language.getValue(), regions, description));
    }

    /** One optional screen area: shows the selection and offers Select / Clear. Saved per game. */
    private final class AreaRow extends HBox {

        private final boolean single;
        private final Label value = new Label();
        private CaptureRegion region;
        private String gameId;

        AreaRow(boolean single, String instruction) {
            this.single = single;
            getStyleClass().add("area-row");
            setAlignment(Pos.CENTER_LEFT);
            Button select = new Button("Select…");
            select.getStyleClass().add("small");
            select.setOnAction(event -> AreaSelector.select(getScene().getWindow(), instruction).ifPresent(this::set));
            Button clear = new Button("Clear");
            clear.getStyleClass().addAll("ghost", "small");
            clear.setOnAction(event -> set(null));
            value.getStyleClass().add("area-value");
            getChildren().addAll(value, select, clear);
        }

        void load(String newGameId, CaptureRegion saved) {
            gameId = Objects.requireNonNull(newGameId);
            region = saved;
            refresh();
        }

        Optional<CaptureRegion> region() {
            return Optional.ofNullable(region);
        }

        private void set(CaptureRegion newRegion) {
            region = newRegion;
            GameSettings settings = store.game(gameId);
            store.saveGame(gameId, single ? settings.withSingleArea(newRegion) : settings.withDoubleArea(newRegion));
            refresh();
        }

        private void refresh() {
            value.setText(region == null ? "Not selected" : region.toString());
            value.getStyleClass().remove("selected");
            if (region != null) {
                value.getStyleClass().add("selected");
            }
        }
    }
}
