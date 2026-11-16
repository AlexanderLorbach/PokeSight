package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.game.GameCategory;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.game.GameOption;
import de.alorbach.pokesight.game.GameRulesets;
import de.alorbach.pokesight.ocr.GlyphFont;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.settings.AppSettings;
import de.alorbach.pokesight.settings.GameSettings;
import de.alorbach.pokesight.settings.SettingsStore;
import de.alorbach.pokesight.update.UpdateCheck;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.util.StringConverter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Start screen: pick the game (= its YAML file), the language and, optionally, where the opponents'
 * names appear in single and double battles and where the player's own name boxes are (one in single battles, two in
 * double battles). Without a selected opponent
 * area the whole main screen is read. Games with options (Platinum Together's MODERN POKéMON) show them as switches.
 * Choices are remembered in the settings files.
 */
public final class StartView extends BorderPane {

    private final SettingsStore store;
    private final ComboBox<GameDefinition> game = new ComboBox<>();
    private final ComboBox<Language> language = new ComboBox<>();
    private final AreaRow singleArea = new AreaRow(GameSettings::withSingleArea,
            "Drag a rectangle around the opponent's name box in a single battle");
    private final AreaRow doubleArea = new AreaRow(GameSettings::withDoubleArea,
            "Drag a rectangle around both opponents' name boxes in a double battle");
    private final AreaRow ownArea = new AreaRow(GameSettings::withOwnArea,
            "Drag a rectangle around your own Pokémon's name box in a single battle");
    private final AreaRow ownDoubleArea = new AreaRow(GameSettings::withOwnDoubleArea,
            "Drag a rectangle around both of your own Pokémon's name boxes in a double battle");
    private final Button forgetLetters = new Button("Forget learned letters");
    private final GridPane form = new GridPane();
    private final FlowPane gameOptions = new FlowPane();

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
        game.setCellFactory(list -> new GameCell(true));
        game.setButtonCell(new GameCell(false));
        game.getStyleClass().add("game-combo");
        language.prefWidthProperty().bind(game.widthProperty());
        gameOptions.getStyleClass().add("game-options");

        form.getStyleClass().add("form");
        layOutForm(null);
        game.valueProperty().addListener((obs, old, selected) -> gameChanged(selected, saved.language()));
        game.setValue(games.stream()
                .filter(g -> g.id().equals(saved.game()))
                .findFirst()
                .orElse(games.isEmpty() ? null : games.getFirst()));

        Label hint = new Label("""
                Areas are optional: drag a rectangle around the opponents' name boxes (without one the whole screen \
                is read, slower) and around your own name boxes. Never let PokeSight cover a selected area.""");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE); // all of it, not cut to one line when the window is small
        HBox.setHgrow(hint, Priority.ALWAYS);

        forgetLetters.getStyleClass().addAll("ghost", "small");
        forgetLetters.setTooltip(new Tooltip("PokeSight learns the letters of the game's font while you play. Forget "
                + "them if names are read wrongly after you changed the emulator's size or screen filter."));
        forgetLetters.setOnAction(event -> forgetLearnedLetters());
        forgetLetters.setMinWidth(Region.USE_PREF_SIZE);
        HBox notes = new HBox(16, hint, forgetLetters);
        notes.setAlignment(Pos.CENTER_LEFT);

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

        VBox card = new VBox(heading, form, notes, start);
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
        label.setMinWidth(Region.USE_PREF_SIZE); // never cut to "Single battle ..."
        return label;
    }

    /**
     * The rows of the form: the language only if the game has more than one, the rules only for games with options
     * (the card has no room for more rows at the default window height).
     */
    private void layOutForm(GameDefinition selected) {
        form.getChildren().clear();
        int row = 0;
        form.addRow(row++, formLabel("Game"), game);
        if (selected == null || selected.languages().size() > 1) {
            form.addRow(row++, formLabel("Language"), language);
        }
        if (selected != null && !selected.options().isEmpty()) {
            form.addRow(row++, formLabel("Rules"), gameOptions);
        }
        form.addRow(row++, formLabel("Single battle area"), singleArea);
        form.addRow(row++, formLabel("Double battle area"), doubleArea);
        form.addRow(row++, formLabel("Your single battle area"), ownArea);
        form.addRow(row, formLabel("Your double battle area"), ownDoubleArea);
    }

    private void gameChanged(GameDefinition selected, Language savedLanguage) {
        if (selected == null) {
            language.getItems().clear();
            return;
        }
        List<Language> available = selected.languages().stream().sorted().toList();
        language.getItems().setAll(available);
        language.setValue(available.contains(savedLanguage) ? savedLanguage : available.getFirst());
        layOutForm(selected);
        gameOptions.getChildren().setAll(selected.options().stream().map(option -> optionBox(selected, option)).toList());
        GameSettings settings = store.game(selected.id());
        singleArea.load(selected.id(), settings.singleArea());
        doubleArea.load(selected.id(), settings.doubleArea());
        ownArea.load(selected.id(), settings.ownArea());
        ownDoubleArea.load(selected.id(), settings.ownDoubleArea());
        forgetLetters.setDisable(!Files.exists(GlyphFont.defaultFile(selected.id())));
    }

    /** A switch of the game, saved in the app settings as soon as it changes. */
    private CheckBox optionBox(GameDefinition selected, GameOption option) {
        CheckBox box = new CheckBox(option.label());
        box.setSelected(GameRulesets.isOn(option, store.app().options(selected.id())));
        box.setTooltip(new Tooltip(option.description()));
        box.selectedProperty().addListener((obs, old, on) -> Theme.saveOrWarn(getScene().getWindow(),
                () -> store.saveApp(store.app().withOption(selected.id(), option.id(), on))));
        return box;
    }

    private void forgetLearnedLetters() {
        GameDefinition selected = game.getValue();
        if (selected == null) {
            return;
        }
        try {
            Files.deleteIfExists(GlyphFont.defaultFile(selected.id()));
            forgetLetters.setDisable(true);
        } catch (IOException e) {
            Theme.showError(getScene().getWindow(), "Cannot forget the learned letters", e.getMessage());
        }
    }

    /**
     * Shows in the title bar that a newer version is available, with a button that opens its download page (the card
     * itself has no room left at the default window height).
     */
    public void showUpdate(UpdateCheck.Release release, Consumer<URI> open) {
        Label text = new Label("PokeSight " + release.version() + " is available");
        text.getStyleClass().add("update-text");
        Button download = new Button("Download");
        download.getStyleClass().addAll("accent", "small");
        download.setOnAction(event -> open.accept(release.page()));
        HBox notice = new HBox(text, download);
        notice.getStyleClass().add("update-notice");
        notice.setAlignment(Pos.CENTER_LEFT);
        setTop(TitleBar.of("PokeSight", null, notice));
    }

    private void submit(Consumer<SessionSettings> onStart) {
        GameDefinition selectedGame = game.getValue();
        if (selectedGame == null || language.getValue() == null) {
            return; // the combo boxes always have a value as long as there is a game
        }
        Theme.saveOrWarn(getScene().getWindow(), () -> store.saveApp(store.app().withGame(selectedGame.id(), language.getValue())));

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
        List<CaptureRegion> ownRegions = new ArrayList<>();
        ownArea.region().ifPresent(ownRegions::add);
        ownDoubleArea.region().ifPresent(ownRegions::add);
        onStart.accept(new SessionSettings(selectedGame, language.getValue(), regions, description, ownRegions,
                store.app().options(selectedGame.id())));
    }

    /**
     * A game in the combo box. ROM hacks and custom games carry a badge ("ROM hack", "Custom"); in the drop-down list
     * the first game of each of these categories also has a heading above it ("ROM hacks", "Custom games"), which is
     * no part of the selectable row: it is never highlighted and ignores clicks.
     */
    private static final class GameCell extends ListCell<GameDefinition> {

        private static final String SECTION_START = "section-start";

        private final boolean headings;
        private final Label badge = categoryBadge();
        // Graphic of the first game of a category: heading, then the row that is highlighted instead of the cell
        private final Label heading = new Label();
        private final Label rowName = new Label();
        private final Label rowBadge = categoryBadge();
        private final VBox sectionStart;

        GameCell(boolean headings) {
            this.headings = headings;
            heading.getStyleClass().addAll("section-title", "combo-section");
            heading.setMaxWidth(Double.MAX_VALUE);
            heading.addEventFilter(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
            heading.addEventFilter(MouseEvent.MOUSE_RELEASED, MouseEvent::consume);
            heading.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
            rowName.getStyleClass().add("game-name");
            HBox row = new HBox(6, rowName, rowBadge);
            row.getStyleClass().add("game-row");
            row.setAlignment(Pos.CENTER_LEFT);
            sectionStart = new VBox(heading, row);
            sectionStart.prefWidthProperty().bind(widthProperty()); // the highlighted row spans the whole list
        }

        @Override
        protected void updateItem(GameDefinition item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove(SECTION_START);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (headings && isFirstOfSection(item)) {
                getStyleClass().add(SECTION_START);
                heading.setText(sectionTitle(item.category()));
                rowName.setText(item.name());
                rowBadge.setText(badgeText(item.category()));
                setText(null);
                setGraphic(sectionStart);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            } else {
                setText(item.name());
                badge.setText(badgeText(item.category()));
                setGraphic(item.category() == GameCategory.OFFICIAL ? null : badge);
                setContentDisplay(ContentDisplay.RIGHT);
            }
        }

        /** The first ROM hack or custom game in the list: it carries its category's heading. */
        private boolean isFirstOfSection(GameDefinition item) {
            return item.category() != GameCategory.OFFICIAL && getListView() != null
                    && getListView().getItems().stream().filter(game -> game.category() == item.category()).findFirst()
                    .orElse(null) == item;
        }

        private static String sectionTitle(GameCategory category) {
            return switch (category) {
                case OFFICIAL -> "Games";
                case ROM_HACK -> "ROM hacks";
                case CUSTOM -> "Custom games";
            };
        }

        private static String badgeText(GameCategory category) {
            return switch (category) {
                case OFFICIAL -> "";
                case ROM_HACK -> "ROM hack";
                case CUSTOM -> "Custom";
            };
        }

        private static Label categoryBadge() {
            Label badge = new Label();
            badge.getStyleClass().add("rom-hack-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            return badge;
        }
    }

    /** One optional screen area: shows the selection and offers Select / Clear. Saved per game. */
    private final class AreaRow extends HBox {

        private final BiFunction<GameSettings, CaptureRegion, GameSettings> save;
        private final Label value = new Label();
        private CaptureRegion region;
        private String gameId;

        /** @param save how the area is stored in the game's settings */
        AreaRow(BiFunction<GameSettings, CaptureRegion, GameSettings> save, String instruction) {
            this.save = save;
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
            Theme.saveOrWarn(getScene().getWindow(), () -> store.saveGame(gameId, save.apply(settings, newRegion)));
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
