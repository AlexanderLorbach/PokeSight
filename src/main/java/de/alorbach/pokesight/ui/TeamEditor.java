package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.capture.CaptureRegion;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.AWTException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Window to enter the player's team: up to six Pokémon with up to four moves each. */
final class TeamEditor {

    private static final double SPRITE_SIZE = 40;

    private TeamEditor() {
    }

    static void show(Window owner, Team team, Ruleset ruleset, Language language, SpriteLibrary sprites, TeamScan teamScan,
                     Consumer<Team> onSave) {
        Stage stage = new Stage();
        Scene scene = new Scene(content(team, ruleset, language, sprites, teamScan, onSave, stage::close), Theme.DEFAULT_WIDTH, Theme.DEFAULT_HEIGHT);
        Theme.apply(scene);
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("PokeSight - team");
        Theme.decorate(stage);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * The editor's content: title bar with Name area / Moves area / Save / Cancel, six team slots and a note on how
     * moves are ranked.
     *
     * @param teamScan reads a Pokémon's name and moves from the screen; null hides the Read buttons (tests)
     */
    static BorderPane content(Team team, Ruleset ruleset, Language language, SpriteLibrary sprites, TeamScan teamScan,
                              Consumer<Team> onSave, Runnable onClose) {
        List<SearchField.Option<PokemonId>> pokemonOptions = SearchField.pokemon(ruleset, language);
        List<SearchField.Option<Integer>> moveOptions = SearchField.moves(ruleset, language);
        List<Row> rows = new ArrayList<>();
        GridPane grid = new GridPane();
        grid.getStyleClass().add("team-grid");
        grid.addRow(0, new Label(), header("Pokémon"), header("Move 1"), header("Move 2"), header("Move 3"), header("Move 4"));
        for (int i = 0; i < Team.MAX_MEMBERS; i++) {
            Team.Member member = i < team.members().size() ? team.members().get(i) : null;
            Row row = new Row(pokemonOptions, moveOptions, sprites, member);
            rows.add(row);
            grid.addRow(i + 1, row.sprite, row.pokemon, row.moves.get(0), row.moves.get(1), row.moves.get(2), row.moves.get(3));
            if (teamScan != null) {
                grid.add(readButton(row, teamScan), 6, i + 1);
            }
            grid.add(row.clear, 7, i + 1);
        }

        Label hint = new Label("Ranking per opponent: move power × type effectiveness × 1.5 if the move has its "
                + "Pokémon's type (STAB) × your Pokémon's Attack (Sp. Atk) / the opponent's Defense (Sp. Def), base "
                + "stats. Only damaging moves with a fixed power can be chosen. Read: open your Pokémon's move "
                + "selection or summary in the game and press Read in a row; the moves are read from the moves area and "
                + "the Pokémon from the name area (both selected once per game; the name area is optional). Read again "
                + "after moving the cursor if a move was missed: the highlighted one is often not read.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        Button save = new Button("Save team");
        save.getStyleClass().addAll("primary", "small");
        save.setDefaultButton(true);
        save.setOnAction(event -> {
            List<Team.Member> members = new ArrayList<>();
            rows.forEach(row -> row.member().ifPresent(members::add));
            onSave.accept(new Team(members));
            onClose.run();
        });
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("ghost", "small");
        cancel.setCancelButton(true);
        cancel.setOnAction(event -> onClose.run());

        VBox content = new VBox(16, grid, hint);
        content.getStyleClass().add("team-editor");
        BorderPane root = new BorderPane(content);
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
            root.setTop(TitleBar.of("Your team", null, nameArea, movesArea, save, cancel));
        } else {
            root.setTop(TitleBar.of("Your team", null, save, cancel));
        }
        return root;
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
    private static Button readButton(Row row, TeamScan teamScan) {
        Button read = new Button("Read");
        read.getStyleClass().addAll("ghost", "small");
        read.setTooltip(new Tooltip("Read this Pokémon (name area) and its moves (moves area) from the game"));
        read.setOnAction(event -> {
            if (teamScan.movesArea().isEmpty() && selectMovesArea(read, teamScan).isEmpty()) {
                return;
            }
            read.setDisable(true);
            read.setText("Reading…");
            Thread.ofVirtual().name("team-reader").start(() -> {
                try {
                    TeamScan.Reading reading = teamScan.read();
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

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
    }

    /** One team slot: sprite, Pokémon and four moves. */
    private static final class Row {
        final StackPane sprite = new StackPane();
        final SearchField<PokemonId> pokemon;
        final List<SearchField<Integer>> moves = new ArrayList<>();
        final Button clear = new Button("Clear");
        private final SpriteLibrary sprites;

        Row(List<SearchField.Option<PokemonId>> pokemonOptions, List<SearchField.Option<Integer>> moveOptions,
            SpriteLibrary sprites, Team.Member member) {
            this.sprites = sprites;
            sprite.getStyleClass().add("team-sprite");
            sprite.setMinSize(SPRITE_SIZE, SPRITE_SIZE);
            pokemon = new SearchField<>(pokemonOptions, "Pokémon…", true, chosen -> showSprite(sprites, chosen));
            pokemon.getStyleClass().add("team-pokemon");
            for (int i = 0; i < Team.MAX_MOVES; i++) {
                moves.add(new SearchField<>(moveOptions, "Move…", true, chosen -> { }));
            }
            clear.getStyleClass().addAll("ghost", "small");
            clear.setOnAction(event -> {
                pokemon.select(null);
                moves.forEach(move -> move.select(null));
                sprite.getChildren().clear();
            });
            if (member != null) {
                pokemon.select(member.pokemon());
                showSprite(sprites, member.pokemon());
                for (int i = 0; i < member.moves().size(); i++) {
                    moves.get(i).select(member.moves().get(i));
                }
            }
            sprite.setAlignment(Pos.CENTER);
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
            showSprite(sprites, found);
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
                    moves.stream().map(SearchField::selected).flatMap(Optional::stream).toList()));
        }
    }
}
