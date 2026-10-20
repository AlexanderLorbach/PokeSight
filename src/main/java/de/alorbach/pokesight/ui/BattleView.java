package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.BattleMonitor;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.EnemyAnalysis;
import de.alorbach.pokesight.matchup.MatchupCalculator;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.settings.SettingsStore;
import de.alorbach.pokesight.sprite.SpriteCache;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Live view: one card per opposing Pokémon. The user can force a scan, correct a misread Pokémon, add one by hand,
 * set their team and check what the scan captured. In borderless mode only the cards remain and fill the window
 * (one opponent: all of it, two: one half each). Wire the actions with the {@code setOn...} methods; all methods
 * must be called on the JavaFX thread.
 */
public final class BattleView extends BorderPane {

    /** Opponents side by side: a double battle shows its two opponents left and right. */
    private static final int COLUMNS = 2;

    private final MatchupCalculator calculator;
    private final Language language;
    private final String gameId;
    private final SettingsStore store;
    private final SpriteLibrary sprites;
    private final List<SearchField.Option<PokemonId>> pokemonOptions;
    private final BattleBoard board = new BattleBoard();
    private final Label status = new Label("Starting…");
    private final ToggleButton alwaysOnTop = new ToggleButton("Always on top");
    private final ToggleButton borderless = new ToggleButton("Borderless");
    private final Node top;
    private final GridPane cards = new GridPane();
    private final ScrollPane cardsScroll = new ScrollPane(cards);
    private final VBox compactCards = new VBox();
    /** Borderless mode: the cards plus a button bar that shows while the mouse is over the window. */
    private final StackPane compactRoot = new StackPane();
    private final VBox emptyState;

    private Team team;
    private Runnable onScanNow = () -> { };
    private Runnable onReset = () -> { };
    private Supplier<List<BufferedImage>> captures = List::of;
    private double dragX;
    private double dragY;

    public BattleView(SessionSettings settings, MatchupCalculator calculator, SettingsStore store, Runnable onBack) {
        this.calculator = calculator;
        this.language = settings.language();
        this.gameId = settings.game().id();
        this.store = store;
        this.sprites = new SpriteLibrary(SpriteCache.forGame(settings.game()).orElse(null), calculator.ruleset());
        this.pokemonOptions = SearchField.pokemon(calculator.ruleset(), language);
        this.team = store.game(gameId).toTeam();
        getStyleClass().add("battle-view");

        top = new VBox(header(onBack), actionBar());
        setTop(top);
        cards.getStyleClass().add("cards");
        cardsScroll.setFitToWidth(true);
        cardsScroll.setFitToHeight(true); // short cards are stretched to the bottom; taller content scrolls
        compactCards.getStyleClass().add("compact-view");
        setUpBorderlessControls();

        Label emptyTitle = new Label("Waiting for a battle");
        emptyTitle.getStyleClass().add("empty-title");
        Label emptyText = new Label("Start a battle, press Scan now, or add a Pokémon.");
        emptyText.getStyleClass().add("empty-text");
        Node ball = PokeBall.of(72);
        ball.setOpacity(0.35);
        emptyState = new VBox(ball, emptyTitle, emptyText);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        VBox.setVgrow(emptyState, Priority.ALWAYS);
        render();
        sprites.downloadMissing(this::render); // first start: the cards get their sprites once the download is done
    }

    private Node header(Runnable onBack) {
        status.getStyleClass().add("status-pill");
        alwaysOnTop.getStyleClass().addAll("ghost", "small");
        alwaysOnTop.selectedProperty().addListener((obs, old, selected) -> stage().ifPresent(stage -> stage.setAlwaysOnTop(selected)));
        borderless.getStyleClass().addAll("ghost", "small");
        borderless.selectedProperty().addListener((obs, old, selected) -> setBorderless(selected));
        if (!TitleBar.extendedWindowsEnabled()) {
            borderless.setDisable(true);
            borderless.setTooltip(new Tooltip("Needs -Djavafx.enablePreview=true (set by the Gradle run and the packaged app)"));
        }
        Button back = new Button("Change game");
        back.getStyleClass().addAll("ghost", "small");
        back.setOnAction(event -> onBack.run());
        return TitleBar.of("PokeSight", null, status, alwaysOnTop, borderless, back);
    }

    private HBox actionBar() {
        Button scanNow = new Button("Scan now");
        scanNow.getStyleClass().add("accent");
        scanNow.setOnAction(event -> onScanNow.run());
        Button clear = new Button("Clear");
        clear.setOnAction(event -> clear());
        Button teamButton = new Button("Team");
        teamButton.setOnAction(event -> editTeam());
        Button preview = new Button("Capture preview");
        preview.setOnAction(event -> CapturePreview.show(getScene().getWindow(), captures));
        SearchField<PokemonId> add = new SearchField<>(pokemonOptions, "Add Pokémon…", false, pokemon -> {
            board.add(pokemon);
            render();
        });
        HBox bar = new HBox(scanNow, clear, teamButton, preview, spacer(), add);
        bar.getStyleClass().add("action-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Borderless mode: a small button bar appears in the top right corner while the mouse is over the window;
     * Esc also leaves the mode, and dragging the cards moves the window.
     */
    private void setUpBorderlessControls() {
        Button leave = new Button("Exit borderless");
        leave.setOnAction(event -> borderless.setSelected(false));
        ToggleButton onTop = new ToggleButton("Always on top");
        onTop.selectedProperty().bindBidirectional(alwaysOnTop.selectedProperty());
        Button scan = new Button("Scan now");
        scan.setOnAction(event -> onScanNow.run());
        Button clearButton = new Button("Clear");
        clearButton.setOnAction(event -> clear());
        Button teamButton = new Button("Team");
        teamButton.setOnAction(event -> editTeam());
        HBox hoverBar = new HBox(6, scan, clearButton, teamButton, onTop, leave);
        hoverBar.getChildren().forEach(button -> button.getStyleClass().addAll("ghost", "small"));
        hoverBar.getStyleClass().add("hover-bar");
        hoverBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        hoverBar.setVisible(false);
        StackPane.setAlignment(hoverBar, Pos.TOP_RIGHT);
        compactRoot.getChildren().setAll(compactCards, hoverBar);
        compactRoot.hoverProperty().addListener((obs, old, hovering) -> hoverBar.setVisible(hovering));

        compactCards.setOnMousePressed(event -> stage().ifPresent(stage -> {
            dragX = event.getScreenX() - stage.getX();
            dragY = event.getScreenY() - stage.getY();
        }));
        compactCards.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                stage().ifPresent(stage -> {
                    stage.setX(event.getScreenX() - dragX);
                    stage.setY(event.getScreenY() - dragY);
                });
            }
        });
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == KeyCode.ESCAPE && borderless.isSelected()) {
                        borderless.setSelected(false);
                        event.consume();
                    }
                });
            }
        });
    }

    private void setBorderless(boolean on) {
        setTop(on ? null : top);
        stage().ifPresent(stage -> TitleBar.showWindowButtons(stage, !on));
        render();
    }

    private void editTeam() {
        TeamEditor.show(getScene().getWindow(), team, calculator.ruleset(), language, sprites,
                new MoveScan(store, gameId, calculator.ruleset(), language), saved -> {
            Theme.saveOrWarn(getScene().getWindow(), () -> store.saveGame(gameId, store.game(gameId).withTeam(saved)));
            useTeam(saved);
        });
    }

    /** Shows the best moves of this team (not saved; see {@link #editTeam}). */
    void useTeam(Team newTeam) {
        team = newTeam;
        render();
    }

    /** Same as the Borderless toggle in the title bar. */
    void selectBorderless(boolean on) {
        borderless.setSelected(on);
    }

    private void clear() {
        board.clear();
        render();
        onReset.run();
    }

    public void setOnScanNow(Runnable action) {
        this.onScanNow = action;
    }

    public void setOnReset(Runnable action) {
        this.onReset = action;
    }

    public void setCaptureSupplier(Supplier<List<BufferedImage>> supplier) {
        this.captures = supplier;
    }

    public void show(BattleMonitor.Update update) {
        status.setText(update.message());
        status.getStyleClass().removeIf(style -> style.startsWith("state-"));
        status.getStyleClass().add("state-" + update.state().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        List<BattleBoard.Entry> before = board.entries();
        board.update(update.state(), update.foes());
        // Rebuilding the cards would close a search field the user is typing in; only do it when they change.
        if (!board.entries().equals(before)) {
            render();
        }
    }

    private void render() {
        boolean compact = borderless.isSelected();
        PokemonCard.Context context = new PokemonCard.Context(calculator.ruleset(), language, pokemonOptions, !team.isEmpty(),
                sprites::image);
        List<Node> enemyCards = new ArrayList<>();
        for (BattleBoard.Entry entry : board.entries()) {
            Optional<EnemyAnalysis> analysis = calculator.analyze(entry.pokemon(), language, team);
            PokemonCard card = new PokemonCard(entry, analysis, sprites.image(entry.pokemon()), context, compact,
                    chosen -> {
                        board.change(entry, chosen);
                        render();
                    },
                    entry.added() ? () -> {
                        board.remove(entry);
                        render();
                    } : null);
            // Borderless: each opponent gets an equal share of the window, all of it or one half each.
            enemyCards.add(compact ? new FitPane(card) : card);
        }
        if (compact) {
            GridPane grid = new GridPane();
            grid.getStyleClass().add("compact-grid");
            arrange(grid, enemyCards, true);
            VBox.setVgrow(grid, Priority.ALWAYS);
            compactCards.getChildren().setAll(enemyCards.isEmpty() ? List.of(emptyState) : List.of(grid));
            cards.getChildren().clear();
            setCenter(compactRoot);
        } else {
            compactCards.getChildren().clear();
            arrange(cards, enemyCards, false);
            setCenter(enemyCards.isEmpty() ? emptyState : cardsScroll);
        }
    }

    /**
     * Side by side in equal columns: one opponent fills the width, two (double battle) stand left and right, more
     * (triple battles, hordes) continue in rows of {@value #COLUMNS}.
     *
     * @param fillHeight rows share the height equally (borderless); otherwise they grow from the cards' heights
     */
    static void arrange(GridPane grid, List<Node> nodes, boolean fillHeight) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        int columns = Math.max(1, Math.min(COLUMNS, nodes.size()));
        for (int column = 0; column < columns; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / columns);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            grid.getColumnConstraints().add(constraints);
        }
        int rows = (nodes.size() + columns - 1) / columns;
        for (int row = 0; row < rows; row++) {
            RowConstraints constraints = new RowConstraints();
            constraints.setValignment(VPos.TOP);
            constraints.setVgrow(Priority.ALWAYS); // the cards fill the window, also when they are shorter
            constraints.setFillHeight(true);
            if (fillHeight) {
                constraints.setPercentHeight(100.0 / rows);
            }
            grid.getRowConstraints().add(constraints);
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
                region.setMinWidth(0);
                region.setMaxHeight(Double.MAX_VALUE);
            }
            grid.add(node, i % columns, i / columns);
        }
    }

    private Optional<Stage> stage() {
        return getScene() != null && getScene().getWindow() instanceof Stage stage ? Optional.of(stage) : Optional.empty();
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
