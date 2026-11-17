package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.AttackStyle;
import de.alorbach.pokesight.matchup.MovePool;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.matchup.TeamCoverage;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.Species;
import de.alorbach.pokesight.ruleset.TypeChart;
import de.alorbach.pokesight.capture.CaptureRegion;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.AWTException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Window to enter the player's team: up to six Pokémon with up to four moves each. */
final class TeamEditor {

    private static final double SPRITE_SIZE = 48;
    private static final double MINI_SPRITE_SIZE = 24;
    /** Window border and scroll bar beyond the content's preferred size. */
    private static final double WINDOW_EXTRA = 24;
    /** The editor window takes at most this share of the screen. */
    private static final double MAX_SCREEN_SHARE = 0.9;
    /** Coverage suggestions shown at most, so the panel stays short; each names the types it would cover. */
    private static final int SUGGESTIONS_SHOWN = 3;
    /** Places named under a suggestion (a free slot counts as one): enough to choose, short enough to read. */
    private static final int REPLACEMENTS_SHOWN = 3;
    /** Style class of a move field with a valid move: filled in its type colour. */
    private static final String TYPED = "typed";

    private TeamEditor() {
    }

    /** @param pool the game's learnsets; empty for games without them (ROM hacks) */
    static void show(Window owner, Team team, Ruleset ruleset, Language language, SpriteLibrary sprites, TeamScan teamScan,
                     Optional<MovePool> pool, Consumer<Team> onSave) {
        Stage stage = new Stage();
        BorderPane content = content(team, ruleset, language, sprites, teamScan, pool, onSave, stage::close);
        Scene scene = new Scene(content);
        Theme.apply(scene);
        // As large as the six slots and the coverage need, within the screen; the content scrolls if it is smaller.
        content.applyCss();
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(content.prefWidth(-1) + WINDOW_EXTRA, screen.getWidth() * MAX_SCREEN_SHARE));
        stage.setHeight(Math.min(content.prefHeight(-1) + WINDOW_EXTRA, screen.getHeight() * MAX_SCREEN_SHARE));
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("PokeSight - team");
        Theme.decorate(stage);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * The editor's content: title bar with Name area / Moves area / Save / Cancel, a line per team slot (Pokémon with
     * its types and attack icons, its four moves in their type colours, Read and Clear), the team's coverage and how
     * Read works.
     *
     * @param teamScan reads a Pokémon's name and moves from the screen; null hides the Read buttons (tests)
     * @param pool     the game's learnsets: coverage suggestions then name the move each member would learn, and
     *                 "Learnable moves" lists them; empty for games without them
     */
    static BorderPane content(Team team, Ruleset ruleset, Language language, SpriteLibrary sprites, TeamScan teamScan,
                              Optional<MovePool> pool, Consumer<Team> onSave, Runnable onClose) {
        List<SearchField.Option<PokemonId>> pokemonOptions = SearchField.pokemon(ruleset, language);
        List<SearchField.Option<Integer>> moveOptions = SearchField.moves(ruleset, language);
        List<Row> rows = new ArrayList<>();
        VBox coverage = new VBox();
        coverage.getStyleClass().addAll("matchup-section", "team-coverage");
        // Opens the learnable moves with a search (a suggested type), beside the editor: one window, built when first used.
        AtomicReference<LearnableMovesView> movesView = new AtomicReference<>();
        Consumer<String> openMoves = pool.<Consumer<String>>map(known -> query -> {
            if (movesView.get() == null) {
                movesView.set(LearnableMovesView.of(coverage.getScene().getWindow(), known, ruleset, language, sprites,
                        () -> currentTeam(rows)));
            }
            movesView.get().show(query);
        }).orElse(null);
        Runnable showCoverage = () -> showCoverage(coverage, currentTeam(rows), ruleset, language, sprites, pool, openMoves);
        VBox slots = new VBox();
        slots.getStyleClass().add("team-slots");
        for (int i = 0; i < Team.MAX_MEMBERS; i++) {
            Team.Member member = i < team.members().size() ? team.members().get(i) : null;
            Row row = new Row(pokemonOptions, moveOptions, sprites, ruleset, language, member, showCoverage);
            rows.add(row);
            slots.getChildren().add(slot(row, teamScan, () -> currentTeam(rows).nicknames()));
        }

        Label hint = new Label("Read: show the Pokémon's moves in the game, then press Read. Missed one? Move the cursor "
                + "and read again.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        hint.setVisible(teamScan != null);
        hint.setManaged(teamScan != null);

        Button save = new Button("Save team");
        save.getStyleClass().addAll("primary", "small");
        save.setDefaultButton(true);
        save.setOnAction(event -> {
            onSave.accept(currentTeam(rows));
            onClose.run();
        });
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("ghost", "small");
        cancel.setCancelButton(true);
        cancel.setOnAction(event -> onClose.run());

        showCoverage.run();
        VBox content = new VBox(16, slots, coverage, hint);
        content.getStyleClass().add("team-editor");
        ScrollPane scroll = new ScrollPane(content); // the coverage panel can make the editor taller than the window
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll);
        ToggleButton nicknames = nicknameToggle(rows, root, team.members().stream().anyMatch(Team.Member::hasNickname));
        List<Node> controls = new ArrayList<>(List.of(nicknames));
        if (openMoves != null) {
            Button learnable = new Button("Learnable moves");
            learnable.getStyleClass().addAll("ghost", "small");
            learnable.setTooltip(new Tooltip("Which damaging moves your Pokémon learn in this game, and how: search by move, "
                    + "type, Pokémon or TM"));
            learnable.setOnAction(event -> openMoves.accept(""));
            controls.add(learnable);
        }
        if (teamScan != null) {
            Button nameArea = new Button(teamScan.nameArea().isPresent() ? "Name area ✓" : "Name area…");
            nameArea.getStyleClass().addAll("ghost", "small");
            nameArea.setTooltip(new Tooltip("Where the game shows the Pokémon's name next to its moves (optional): "
                    + "Read then also fills in the Pokémon"));
            nameArea.setOnAction(event -> selectNameArea(nameArea, teamScan).ifPresent(selected -> nameArea.setText("Name area ✓")));
            Button movesArea = new Button(teamScan.movesArea().isPresent() ? "Moves area ✓" : "Moves area…");
            movesArea.getStyleClass().addAll("ghost", "small");
            movesArea.setTooltip(new Tooltip("Where the game shows your Pokémon's moves (the move selection)"));
            movesArea.setOnAction(event -> selectMovesArea(movesArea, teamScan).ifPresent(selected -> movesArea.setText("Moves area ✓")));
            controls.addAll(List.of(nameArea, movesArea));
        }
        controls.addAll(List.of(save, cancel));
        root.setTop(TitleBar.of("Your team", null, controls.toArray(Node[]::new)));
        return root;
    }

    /**
     * "Nicknames": shows a nickname field beside each Pokémon. Off unless a member has one, so the editor stays narrow
     * for players who never nickname; turning it on widens the window as far as the new column needs.
     */
    private static ToggleButton nicknameToggle(List<Row> rows, BorderPane root, boolean on) {
        ToggleButton toggle = new ToggleButton("Nicknames");
        toggle.getStyleClass().addAll("ghost", "small");
        toggle.setTooltip(new Tooltip("A nicknamed Pokémon's name box in battle shows its nickname: enter it so PokeSight "
                + "knows it is out"));
        toggle.selectedProperty().addListener((obs, old, selected) -> {
            rows.forEach(row -> row.showNickname(selected));
            Platform.runLater(() -> widenToFit(root));
        });
        toggle.setSelected(on);
        rows.forEach(row -> row.showNickname(on));
        return toggle;
    }

    /** Widens the editor's window (never narrows it) to the content's preferred width, within the screen. */
    private static void widenToFit(BorderPane root) {
        if (root.getScene() == null || root.getScene().getWindow() == null) {
            return;
        }
        Window window = root.getScene().getWindow();
        double needed = Math.min(root.prefWidth(-1) + WINDOW_EXTRA, Screen.getPrimary().getVisualBounds().getWidth() * MAX_SCREEN_SHARE);
        if (window.getWidth() < needed) {
            window.setWidth(needed);
        }
    }

    /**
     * One team slot on one line: sprite | Pokémon over its types and attack icons | nickname (if shown) | four moves,
     * each over its type and category | Read and Clear. All slots have the same fixed column widths (theme.css), so
     * their columns line up.
     *
     * @param nicknames the team's nicknames as entered so far, so Read recognizes a nickname in the name area
     */
    private static HBox slot(Row row, TeamScan teamScan, Supplier<Map<String, PokemonId>> nicknames) {
        HBox below = new HBox(6, row.types, row.styleIcons); // the attack icons right beside the types, where the eye is
        below.getStyleClass().add("team-slot-info");
        below.setAlignment(Pos.CENTER_LEFT);
        VBox pokemon = new VBox(4, row.pokemon, below);
        pokemon.getStyleClass().add("team-slot-pokemon");
        HBox slot = new HBox(8, row.sprite, pokemon, row.nicknameCell);
        for (int i = 0; i < row.moves.size(); i++) {
            slot.getChildren().add(new VBox(4, row.moves.get(i), row.moveInfos.get(i)));
        }
        VBox buttons = new VBox(4);
        if (teamScan != null) {
            buttons.getChildren().add(readButton(row, teamScan, nicknames));
        }
        buttons.getChildren().add(row.clear);
        buttons.getChildren().forEach(button -> ((Region) button).setMaxWidth(Double.MAX_VALUE));
        slot.getChildren().add(buttons);
        slot.getStyleClass().add("team-slot");
        slot.setAlignment(Pos.CENTER_LEFT);
        for (Node part : slot.getChildren()) {
            ((Region) part).setMinWidth(Region.USE_PREF_SIZE);
        }
        return slot;
    }

    /** The team as entered so far: rows without a Pokémon are left out. */
    private static Team currentTeam(List<Row> rows) {
        return new Team(rows.stream().map(Row::member).flatMap(Optional::stream).toList());
    }

    /**
     * "TEAM COVERAGE 16 / 18": every type as a badge in two even rows, faded where no move of the team hits it super
     * effectively (hover: which moves do), then the move types that would hit most of the faded ones ("Add a GROUND
     * move: hits ELECTRIC ROCK") and where such a move could go. With learnsets, a suggestion no member can learn is
     * faded, and clicking its type lists the team's moves of that type.
     *
     * @param openMoves opens the learnable moves with a search; null without learnsets
     */
    private static void showCoverage(VBox panel, Team team, Ruleset ruleset, Language language, SpriteLibrary sprites,
                                     Optional<MovePool> pool, Consumer<String> openMoves) {
        TypeChart chart = ruleset.typeChart();
        TeamCoverage coverage = TeamCoverage.of(team, ruleset, pool);
        List<PokemonType> types = List.copyOf(chart.types());
        Label title = new Label("TEAM COVERAGE");
        title.getStyleClass().add("section-title");
        HBox header = new HBox(10, title);
        header.setAlignment(Pos.BASELINE_LEFT);
        panel.getChildren().setAll(header);
        if (coverage.moveTypes().isEmpty()) {
            panel.getChildren().add(coverageNote("Choose damaging moves to see which types your team hits super effectively.", false));
            return;
        }
        Label count = new Label((types.size() - coverage.unhit().size()) + " / " + types.size() + " types hit super effectively");
        count.getStyleClass().addAll("coverage-count", coverage.unhit().isEmpty() ? "coverage-good" : "coverage-gaps");
        header.getChildren().add(count);

        List<Move> moves = team.members().stream().flatMap(member -> member.moves().stream())
                .map(ruleset::findMove).flatMap(Optional::stream).distinct().toList();
        // Two even rows across the panel's width.
        int columns = (types.size() + 1) / 2;
        GridPane chartTiles = new GridPane();
        chartTiles.getStyleClass().add("coverage-chart");
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            chartTiles.getColumnConstraints().add(column);
        }
        for (int i = 0; i < types.size(); i++) {
            PokemonType type = types.get(i);
            Label badge = PokemonCard.typeBadge(type, chart, language);
            badge.setMaxWidth(Double.MAX_VALUE);
            badge.setAlignment(Pos.CENTER);
            List<String> hitters = moves.stream().filter(move -> chart.multiplier(move.type(), type) > 1)
                    .map(move -> move.name(language)).toList();
            if (coverage.unhit().contains(type)) {
                badge.getStyleClass().add("uncovered");
                badge.setTooltip(new Tooltip("No move of your team hits " + badge.getText() + " super effectively"));
            } else {
                badge.setTooltip(new Tooltip("Super effective: " + String.join(", ", hitters)));
            }
            chartTiles.add(badge, i % columns, i / columns);
        }
        panel.getChildren().add(chartTiles);

        for (TeamCoverage.Suggestion suggestion : coverage.suggestions().stream().limit(SUGGESTIONS_SHOWN).toList()) {
            FlowPane line = PokemonCard.badges(suggestion.hits(), chart, language);
            Label add = new Label("Add a");
            Label hits = new Label("move: hits");
            add.getStyleClass().add("coverage-tip");
            hits.getStyleClass().add("coverage-tip");
            String typeName = chart.name(suggestion.moveType(), language);
            Label moveType = PokemonCard.typeBadge(suggestion.moveType(), chart, language);
            if (openMoves != null) {
                moveType.getStyleClass().add("clickable");
                moveType.setTooltip(new Tooltip("All " + typeName + " moves your team learns"));
                moveType.setOnMouseClicked(event -> openMoves.accept(typeName));
            }
            line.getChildren().addAll(0, List.of(add, moveType, hits));
            line.setAlignment(Pos.CENTER_LEFT);
            panel.getChildren().add(line);
            if (suggestion.placeable()) {
                panel.getChildren().add(insteadOf(suggestion, ruleset, language, sprites, pool));
            } else {
                line.getStyleClass().add("unlearnable");
                Tooltip.install(line, new Tooltip("No Pokémon of your team learns a " + typeName + " move"));
            }
        }
    }

    /**
     * Under a suggestion, where the new move could go, one row each: the team members with an empty move slot (it costs
     * nothing), then the moves it could replace without losing any type the team hits super effectively (or, if every
     * move covers something, those losing the fewest types, each with what it loses). With learnsets, each place names
     * the move the member would learn and the easiest way ("Earthquake TM26").
     */
    private static GridPane insteadOf(TeamCoverage.Suggestion suggestion, Ruleset ruleset, Language language,
                                      SpriteLibrary sprites, Optional<MovePool> pool) {
        GridPane places = new GridPane();
        places.getStyleClass().add("coverage-places");
        ColumnConstraints chips = new ColumnConstraints();
        chips.setHgrow(Priority.ALWAYS); // the chips wrap at the panel's width
        places.getColumnConstraints().addAll(new ColumnConstraints(), chips);
        int row = 0;
        if (!suggestion.emptySlots().isEmpty()) {
            FlowPane members = placeLine();
            for (TeamCoverage.Slot slot : suggestion.emptySlots()) {
                HBox place = place(memberChip(slot.member(), ruleset, language, sprites));
                slot.learn().ifPresent(learn -> place.getChildren().addAll(learned(learn, language, pool)));
                members.getChildren().add(place);
            }
            places.addRow(row++, placeLabel("Empty move slot"), members);
        }
        List<TeamCoverage.Replacement> replacements = suggestion.replacements().stream().limit(REPLACEMENTS_SHOWN).toList();
        if (!replacements.isEmpty()) {
            FlowPane moves = placeLine();
            for (TeamCoverage.Replacement replacement : replacements) {
                ruleset.findMove(replacement.moveId()).ifPresent(move -> {
                    Label moveName = new Label(move.name(language));
                    moveName.getStyleClass().addAll("type-badge", PokemonCard.typeStyle(move.type()));
                    HBox place = place(memberChip(replacement.member(), ruleset, language, sprites), moveName);
                    replacement.learn().ifPresent(learn -> {
                        Label arrow = new Label("→");
                        arrow.getStyleClass().add("coverage-label");
                        place.getChildren().add(arrow);
                        place.getChildren().addAll(learned(learn, language, pool));
                    });
                    if (!replacement.lost().isEmpty()) {
                        Label loses = new Label("loses");
                        loses.getStyleClass().add("coverage-loss");
                        place.getChildren().add(loses);
                        replacement.lost().forEach(type -> place.getChildren().add(PokemonCard.typeBadge(type, ruleset.typeChart(), language)));
                    }
                    moves.getChildren().add(place);
                });
            }
            boolean free = replacements.getFirst().lost().isEmpty();
            places.addRow(row, placeLabel(free ? "Or replace (keeps all coverage)" : "Or replace (loses the least)"), moves);
        }
        return places;
    }

    /** One place in a row of {@link #insteadOf}, kept together when the row wraps. */
    private static HBox place(Node... parts) {
        HBox place = new HBox(4, parts);
        place.setAlignment(Pos.CENTER_LEFT);
        place.setMinWidth(Region.USE_PREF_SIZE);
        return place;
    }

    /** The move a member would learn: its category, its name in its type colour and the easiest way ("TM26"). */
    private static List<Node> learned(MovePool.Learnable learn, Language language, Optional<MovePool> pool) {
        Label name = new Label(learn.move().name(language));
        name.getStyleClass().addAll("type-badge", PokemonCard.typeStyle(learn.move().type()));
        List<Node> parts = new ArrayList<>(List.of(CategoryIcon.ofMove(learn.move().category()), name));
        pool.ifPresent(known -> {
            Label how = new Label(LearnableMovesView.easiest(learn, known));
            how.getStyleClass().add("coverage-label");
            how.setTooltip(new Tooltip(LearnableMovesView.how(learn, known)));
            parts.add(how);
        });
        return parts;
    }

    private static Label placeLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("coverage-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        GridPane.setValignment(label, VPos.CENTER);
        return label;
    }

    private static FlowPane placeLine() {
        FlowPane line = new FlowPane();
        line.getStyleClass().add("badges");
        line.setAlignment(Pos.CENTER_LEFT);
        line.prefWrapLengthProperty().bind(line.widthProperty()); // one line while there is room, wrapped below that
        return line;
    }

    /** A team member as its mini sprite and name. */
    private static Label memberChip(PokemonId id, Ruleset ruleset, Language language, SpriteLibrary sprites) {
        Label chip = new Label(pokemonName(ruleset, id, language));
        chip.getStyleClass().add("member-chip");
        sprites.image(id).ifPresent(image -> {
            ImageView view = new ImageView(image);
            view.setFitWidth(MINI_SPRITE_SIZE);
            view.setPreserveRatio(true);
            chip.setGraphic(view);
        });
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    private static String pokemonName(Ruleset ruleset, PokemonId pokemon, Language language) {
        return ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()).name(language)).orElse("?");
    }

    private static Label coverageNote(String text, boolean good) {
        Label note = new Label(text);
        note.getStyleClass().addAll("coverage-tip", good ? "coverage-good" : "hint");
        note.setWrapText(true);
        return note;
    }

    private static Optional<CaptureRegion> selectMovesArea(Node owner, TeamScan teamScan) {
        Optional<CaptureRegion> area = AreaSelector.select(owner.getScene().getWindow(),
                "Drag a rectangle around the move names of the game's move selection");
        area.ifPresent(selected -> Theme.saveOrWarn(owner.getScene().getWindow(), () -> teamScan.setMovesArea(selected)));
        return area;
    }

    private static Optional<CaptureRegion> selectNameArea(Node owner, TeamScan teamScan) {
        Optional<CaptureRegion> area = AreaSelector.select(owner.getScene().getWindow(),
                "Drag a rectangle around the Pokémon's name on the same screen as its moves");
        area.ifPresent(selected -> Theme.saveOrWarn(owner.getScene().getWindow(), () -> teamScan.setNameArea(selected)));
        return area;
    }

    /**
     * Reads the Pokémon on screen into the row: OCR runs off the JavaFX thread. A name that shows another Pokémon than
     * the row has replaces it, with its moves; otherwise the found moves fill the empty move fields (moves already in
     * the row are skipped), so reading again after moving the cursor adds missed moves.
     */
    private static Button readButton(Row row, TeamScan teamScan, Supplier<Map<String, PokemonId>> nicknames) {
        Button read = new Button("Read");
        read.getStyleClass().addAll("ghost", "small");
        read.setTooltip(new Tooltip("Read this Pokémon (name area) and its moves (moves area) from the game"));
        read.setOnAction(event -> {
            if (teamScan.movesArea().isEmpty() && selectMovesArea(read, teamScan).isEmpty()) {
                return;
            }
            read.setDisable(true);
            read.setText("Reading…");
            Map<String, PokemonId> known = nicknames.get(); // on the JavaFX thread, before reading off it
            Thread.ofVirtual().name("team-reader").start(() -> {
                try {
                    TeamScan.Reading reading = teamScan.read(known);
                    Platform.runLater(() -> {
                        boolean newPokemon = reading.pokemon().filter(found -> row.setPokemon(found)).isPresent();
                        int added = row.addMoves(reading.moves());
                        read.setText(added == 0 && !newPokemon ? "None found" : "Read");
                        read.setDisable(false);
                    });
                } catch (IOException | AWTException | RuntimeException e) {
                    Platform.runLater(() -> {
                        read.setText("Read");
                        read.setDisable(false);
                        Theme.showError(read.getScene().getWindow(), "Cannot read the Pokémon", e.getMessage());
                    });
                }
            });
        });
        return read;
    }

    /** One team slot: sprite, Pokémon with its types and four moves. */
    private static final class Row {
        final StackPane sprite = new StackPane();
        final SearchField<PokemonId> pokemon;
        final List<SearchField<Integer>> moves = new ArrayList<>();
        final Button clear = new Button("Clear");
        /** The Pokémon's types as badges; empty without a Pokémon. */
        final HBox types = new HBox(4);
        /** Holds the Pokémon's attack icons ({@link CategoryIcon#forAttacker}); empty without a Pokémon. */
        final HBox styleIcons = new HBox();
        /** The name the player gave it; shown only with the Nicknames toggle on. */
        final TextField nickname = new TextField();
        /** The nickname field over an empty line, so it stays level with the Pokémon and move fields. */
        final VBox nicknameCell;
        /** Under each move field: its type and category; empty without a (valid) move. */
        final List<HBox> moveInfos = new ArrayList<>();
        private final SpriteLibrary sprites;
        private final Ruleset ruleset;
        private final Language language;

        /** @param onChange runs whenever the Pokémon or a move changes (typed, picked, read or cleared) */
        Row(List<SearchField.Option<PokemonId>> pokemonOptions, List<SearchField.Option<Integer>> moveOptions,
            SpriteLibrary sprites, Ruleset ruleset, Language language, Team.Member member, Runnable onChange) {
            this.sprites = sprites;
            this.ruleset = ruleset;
            this.language = language;
            types.getStyleClass().add("team-types");
            types.setAlignment(Pos.CENTER_LEFT);
            sprite.getStyleClass().add("team-sprite");
            // Fixed size, also when empty: the slots' columns line up with or without a Pokémon.
            sprite.setMinSize(SPRITE_SIZE, SPRITE_SIZE);
            sprite.setPrefSize(SPRITE_SIZE, SPRITE_SIZE);
            sprite.setMaxSize(SPRITE_SIZE, SPRITE_SIZE);
            styleIcons.setAlignment(Pos.CENTER_RIGHT);
            pokemon = new SearchField<>(pokemonOptions, "Pokémon…", true, chosen -> {
                showSprite(sprites, chosen);
                showStyle(chosen);
            });
            pokemon.getStyleClass().add("team-pokemon");
            nickname.setPromptText("Nickname…");
            nickname.getStyleClass().add("team-nickname");
            Region nicknameInfo = new Region();
            nicknameInfo.getStyleClass().add("team-slot-info");
            nicknameCell = new VBox(4, nickname, nicknameInfo);
            for (int i = 0; i < Team.MAX_MOVES; i++) {
                SearchField<Integer> field = new SearchField<>(moveOptions, "Move…", true, chosen -> { });
                field.getStyleClass().add("team-move");
                HBox info = new HBox(4);
                info.getStyleClass().add("team-slot-info");
                info.setAlignment(Pos.CENTER_LEFT);
                field.textProperty().addListener((obs, old, text) -> showMove(field, info));
                moves.add(field);
                moveInfos.add(info);
            }
            clear.getStyleClass().addAll("ghost", "small");
            clear.setOnAction(event -> {
                pokemon.select(null);
                moves.forEach(move -> move.select(null));
                nickname.clear();
                sprite.getChildren().clear();
                showStyle(null);
            });
            if (member != null) {
                pokemon.select(member.pokemon());
                nickname.setText(member.nickname());
                showSprite(sprites, member.pokemon());
                showStyle(member.pokemon());
                for (int i = 0; i < member.moves().size(); i++) {
                    moves.get(i).select(member.moves().get(i));
                }
            }
            sprite.setAlignment(Pos.CENTER);
            // After filling in the saved member: the editor shows the coverage once all rows exist.
            pokemon.textProperty().addListener((obs, old, text) -> onChange.run());
            moves.forEach(field -> field.textProperty().addListener((obs, old, text) -> onChange.run()));
        }

        void showNickname(boolean shown) {
            nicknameCell.setVisible(shown);
            nicknameCell.setManaged(shown);
        }

        private void showSprite(SpriteLibrary sprites, PokemonId chosen) {
            sprite.getChildren().setAll(sprites.image(chosen).map(image -> {
                ImageView view = new ImageView(image);
                view.setFitWidth(SPRITE_SIZE);
                view.setPreserveRatio(true);
                return List.of(view);
            }).orElse(List.of()));
        }

        /**
         * The chosen Pokémon's types and attack icons (empty without one); its move fields then list the moves of the
         * kind it hits harder with first.
         */
        private void showStyle(PokemonId chosen) {
            Optional<Species.Form> form = Optional.ofNullable(chosen)
                    .flatMap(id -> ruleset.findSpecies(id.speciesId()).map(species -> species.form(id.form())));
            types.getChildren().setAll(form.map(found -> found.types().stream()
                    .map(type -> PokemonCard.typeBadge(type, ruleset.typeChart(), language)).toList()).orElse(List.of()));
            List<Move.Category> suits = form.map(AttackStyle::of).map(CategoryIcon::categories).orElse(List.of());
            styleIcons.getChildren().setAll(form.<Node>map(CategoryIcon::forAttacker).stream().toList());
            Predicate<Integer> preferred = suits.size() == 1
                    ? moveId -> ruleset.findMove(moveId).filter(move -> move.category() == suits.getFirst()).isPresent()
                    : null;
            moves.forEach(field -> field.prefer(preferred));
        }

        /**
         * Fills a chosen move's field in its type colour and shows its type and category under it; a field without a
         * (valid) move stays plain.
         */
        private void showMove(SearchField<Integer> field, HBox info) {
            Optional<Move> move = field.selected().flatMap(ruleset::findMove);
            field.getStyleClass().removeIf(style -> style.startsWith("type-") || style.equals(TYPED));
            info.getChildren().clear();
            field.setTooltip(null);
            move.ifPresent(found -> {
                field.getStyleClass().addAll(TYPED, PokemonCard.typeStyle(found.type()));
                String typeName = ruleset.typeChart().name(found.type(), language);
                Label type = new Label(typeName.toUpperCase(Locale.ROOT));
                type.getStyleClass().add("team-move-type");
                info.getChildren().addAll(CategoryIcon.ofMove(found.category()), type);
                field.setTooltip(new Tooltip(typeName + " · " + SearchField.categoryName(found.category()) + " · power " + found.power()));
            });
        }

        /**
         * Puts a Pokémon read from the screen into the row. Another Pokémon than the row had replaces it and its
         * moves, which belong to the previous one; the same Pokémon keeps the moves.
         *
         * @return whether the row changed
         */
        boolean setPokemon(PokemonId found) {
            if (pokemon.selected().filter(found::equals).isPresent()) {
                return false;
            }
            pokemon.select(found);
            moves.forEach(move -> move.select(null));
            nickname.clear(); // it belonged to the previous Pokémon
            showSprite(sprites, found);
            showStyle(found);
            return true;
        }

        /** Puts the moves that are not in the row yet into its empty move fields; returns how many were added. */
        int addMoves(List<Integer> found) {
            List<Integer> present = moves.stream().map(SearchField::selected).flatMap(Optional::stream).toList();
            int added = 0;
            for (Integer move : found) {
                if (present.contains(move)) {
                    continue;
                }
                Optional<SearchField<Integer>> empty = moves.stream().filter(field -> field.selected().isEmpty()).findFirst();
                if (empty.isEmpty()) {
                    break;
                }
                empty.get().select(move);
                added++;
            }
            return added;
        }

        /** Empty if no Pokémon is chosen; moves that are not (validly) chosen are left out. */
        Optional<Team.Member> member() {
            return pokemon.selected().map(chosen -> new Team.Member(chosen,
                    moves.stream().map(SearchField::selected).flatMap(Optional::stream).toList(), nickname.getText()));
        }
    }
}
