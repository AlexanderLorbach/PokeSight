package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.EnemyAnalysis;
import de.alorbach.pokesight.matchup.Matchup;
import de.alorbach.pokesight.matchup.MoveOption;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.TypeChart;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.Function;

/**
 * One opposing Pokémon: sprite, name, types, abilities that change its matchups, the team's best moves against it,
 * which team member takes its attacks best and which attack types are (not) effective. With the player's active
 * Pokémon known, its moves are marked and a hint says whether another team member hits harder. The compact variant
 * (borderless mode) drops the buttons, notes and switch-ins; {@link FitPane} scales it to fill.
 */
final class PokemonCard extends VBox {

    private static final double SPRITE_SIZE = 80; // sprites are 56 (Gen 1) to 96 px (Gen 5 and later); scaled to this
    private static final double MINI_SPRITE_SIZE = 28;
    private static final int BEST_MOVES_SHOWN = 3;
    private static final int SWITCH_INS_SHOWN = 3;

    /**
     * What every card of a battle view shares.
     *
     * @param terastal opponents can terastallize (Scarlet/Violet): cards offer a Tera type picker
     * @param active   the player's Pokémon on the field, if known and part of the team; null otherwise
     */
    record Context(Ruleset ruleset, Language language, List<SearchField.Option<PokemonId>> pokemonOptions, boolean teamSet,
                   Function<PokemonId, Optional<Image>> sprites, boolean terastal, PokemonId active) {
    }

    private final HBox header;
    private SearchField<PokemonId> search;

    /**
     * @param analysis empty if the scan could not recognize the name
     * @param onRemove null for scanned Pokémon, which disappear with the battle
     * @param onTera   sets the Tera type (null: none); only used if the game has Terastallization
     */
    PokemonCard(BattleBoard.Entry entry, Optional<EnemyAnalysis> analysis, Optional<Image> sprite, Context context,
                boolean compact, Consumer<PokemonId> onChange, Runnable onRemove, Consumer<PokemonType> onTera) {
        getStyleClass().add("pokemon-card");
        if (compact) {
            getStyleClass().add("compact");
        }
        TypeChart chart = context.ruleset().typeChart();
        Language language = context.language();
        StackPane spriteFrame = new StackPane(sprite.<Node>map(PokemonCard::spriteView).orElseGet(() -> PokeBall.of(36)));
        spriteFrame.getStyleClass().add("sprite-frame");
        Label name = styled(new Label(analysis.map(EnemyAnalysis::name).orElse("Unknown Pokémon")), "pokemon-name");
        VBox titles = new VBox(4, name);
        analysis.ifPresent(enemy -> {
            FlowPane types = badges(enemy.types(), chart, language);
            if (enemy.tera()) {
                Label tera = styled(new Label("TERA"), "tera-badge");
                tera.setTooltip(new Tooltip("Terastallized: it has this type alone now"));
                types.getChildren().addFirst(tera);
            }
            titles.getChildren().add(types);
        });
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        header = new HBox(spriteFrame, titles);
        header.getStyleClass().add("card-header");
        header.setAlignment(Pos.CENTER_LEFT);
        if (!compact) {
            header.getChildren().add(actions(context, onChange, onRemove, onTera));
        }
        getChildren().add(header);

        if (!compact && !entry.readText().isEmpty() && (entry.corrected() || analysis.isEmpty())) {
            String note = entry.corrected() ? " (corrected)" : " - use Change to pick the right Pokémon";
            getChildren().add(styled(new Label("Read: \"" + entry.readText() + "\"" + note), "read-text"));
        }
        analysis.ifPresent(enemy -> {
            if (!compact) {
                enemy.abilities().forEach(note -> getChildren().add(abilityNote(note, chart, language)));
            }
            bestMoves(enemy.bestMoves(), context, compact).ifPresent(getChildren()::add);
            if (!compact) {
                switchIns(enemy.switchIns(), context).ifPresent(getChildren()::add);
            }
            if (!context.teamSet() && !compact) {
                Label hint = styled(new Label("Set your team to see which of your moves hits hardest."), "read-text");
                hint.setWrapText(true);
                getChildren().add(hint);
            }
            getChildren().addAll(sections(enemy.matchup(), chart, language));
        });
    }

    private VBox actions(Context context, Consumer<PokemonId> onChange, Runnable onRemove, Consumer<PokemonType> onTera) {
        Button change = styled(new Button("Change"), "ghost", "small");
        change.setOnAction(event -> toggleSearch(context.pokemonOptions(), onChange));
        VBox actions = new VBox(6, change);
        if (context.terastal()) {
            Button tera = styled(new Button("Tera…"), "ghost", "small");
            tera.setTooltip(new Tooltip("It terastallized: pick its Tera type"));
            ContextMenu types = new ContextMenu();
            MenuItem none = new MenuItem("No Tera");
            none.setOnAction(event -> onTera.accept(null));
            types.getItems().add(none);
            for (PokemonType type : context.ruleset().typeChart().types()) {
                MenuItem item = new MenuItem(context.ruleset().typeChart().name(type, context.language()));
                item.setOnAction(event -> onTera.accept(type));
                types.getItems().add(item);
            }
            tera.setOnAction(event -> types.show(tera, Side.BOTTOM, 0, 0));
            actions.getChildren().add(tera);
        }
        if (onRemove != null) {
            Button remove = styled(new Button("Remove"), "ghost", "small");
            remove.setOnAction(event -> onRemove.run());
            actions.getChildren().add(remove);
        }
        actions.setAlignment(Pos.TOP_RIGHT);
        actions.getChildren().forEach(button -> ((Button) button).setMinWidth(Region.USE_PREF_SIZE)); // never "..."
        actions.setMinWidth(Region.USE_PREF_SIZE);
        return actions;
    }

    private static ImageView spriteView(Image image) {
        ImageView view = new ImageView(image);
        view.setSmooth(true); // the sprite is already enlarged without smoothing (SpriteLibrary): stays crisp at any scale
        view.setPreserveRatio(true);
        view.setFitWidth(SPRITE_SIZE);
        return view;
    }

    /**
     * A small sprite cropped to the Pokémon itself: game sprites leave much of their frame empty, which would make
     * small Pokémon a few pixels wide at this size.
     */
    private static ImageView miniSpriteView(Image image) {
        PixelReader pixels = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; pixels != null && y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixels.getArgb(x, y) >>> 24 != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        ImageView view = new ImageView(image);
        if (maxX >= 0) {
            // Square around the visible pixels, so every Pokémon keeps its proportions in the same box.
            double side = Math.max(maxX - minX, maxY - minY) + 1;
            double centerX = (minX + maxX + 1) / 2.0;
            double centerY = (minY + maxY + 1) / 2.0;
            view.setViewport(new Rectangle2D(centerX - side / 2, centerY - side / 2, side, side));
        }
        view.setSmooth(true);
        view.setPreserveRatio(true);
        view.setFitWidth(MINI_SPRITE_SIZE);
        view.setFitHeight(MINI_SPRITE_SIZE);
        return view;
    }

    private void toggleSearch(List<SearchField.Option<PokemonId>> options, Consumer<PokemonId> onChange) {
        if (search != null) {
            getChildren().remove(search);
            search = null;
            return;
        }
        search = new SearchField<>(options, "Type the correct Pokémon…", false, onChange);
        getChildren().add(getChildren().indexOf(header) + 1, search);
        search.requestFocus();
    }

    /** "Levitate: GROUND 0×", or "May have Levitate: ..." if the species has other abilities too. */
    private static Label abilityNote(EnemyAnalysis.AbilityNote note, TypeChart chart, Language language) {
        String effect = note.effect().wonderGuard()
                ? "only super-effective moves hit"
                : String.join(", ", note.effect().factors().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> chart.name(entry.getKey(), language).toUpperCase(Locale.ROOT) + " " + format(entry.getValue()))
                        .toList());
        Label label = styled(new Label((note.certain() ? "" : "May have ") + note.name() + ": " + effect),
                "ability-note", note.certain() ? "certain" : "possible");
        label.setWrapText(true);
        label.setTooltip(new Tooltip(note.certain()
                ? "Its only ability: already counted in the matchups below"
                : "One of its possible abilities: not counted below, so these types may not work"));
        return label;
    }

    /**
     * "YOUR BEST MOVES": the strongest moves of the team that deal damage at all. With the active Pokémon known, its
     * best move is always listed and marked, and a hint says whether another team member hits harder.
     */
    private static Optional<VBox> bestMoves(List<MoveOption> options, Context context, boolean compact) {
        List<MoveOption> hitting = options.stream().filter(option -> option.score() > 0).toList();
        List<MoveOption> shown = new ArrayList<>(hitting.stream().limit(BEST_MOVES_SHOWN).toList());
        Optional<MoveOption> activeBest = context.active() == null ? Optional.empty()
                : hitting.stream().filter(option -> option.user().equals(context.active())).findFirst();
        activeBest.filter(option -> !shown.contains(option)).ifPresent(shown::add);
        if (shown.isEmpty()) {
            return Optional.empty();
        }
        // A grid, so the column headers line up with the rows.
        GridPane moves = new GridPane();
        moves.getStyleClass().add("best-moves-grid");
        if (compact) {
            moves.setMinWidth(Region.USE_PREF_SIZE); // FitPane scales the whole card instead of cutting off columns
        }
        ColumnConstraints pokemonColumn = new ColumnConstraints();
        pokemonColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints scoreColumn = new ColumnConstraints();
        scoreColumn.setHalignment(HPos.RIGHT);
        moves.getColumnConstraints().addAll(new ColumnConstraints(), new ColumnConstraints(), new ColumnConstraints(),
                pokemonColumn, scoreColumn);
        moves.addRow(0,
                columnHeader("EFFECT", "Type effectiveness of the move against this Pokémon"),
                columnHeader("MOVE", "Your team's move"),
                columnHeader("TYPE", "The move's type"),
                columnHeader("POKÉMON", "Your Pokémon that knows the move; STAB = the move has its type (1.5× damage)"),
                columnHeader("SCORE", "Power × effectiveness × 1.5 with STAB × your Pokémon's Attack (Sp. Atk for "
                        + "special moves) / its Defense (Sp. Def), base stats. Ranks the moves; not the real damage"));

        Ruleset ruleset = context.ruleset();
        Language language = context.language();
        int rowIndex = 1;
        for (MoveOption option : shown) {
            Move move = ruleset.findMove(option.moveId()).orElseThrow();
            String user = ruleset.findSpecies(option.user().speciesId())
                    .map(species -> species.form(option.user().form()).name(language)).orElse("?");
            Label multiplier = styled(new Label(format(option.effectiveness()) + (option.caveat() != null ? " ?" : "")),
                    "multiplier", multiplierStyle(option.effectiveness()));
            if (option.caveat() != null) {
                multiplier.setTooltip(new Tooltip("It may have " + option.caveat() + ": this move may not work as shown"));
            }
            multiplier.setMinWidth(Region.USE_PREF_SIZE);
            Label moveName = styled(new Label(move.name(language)), "best-move-name", SearchField.moveTypeStyle(move.type()));
            Label type = styled(new Label(ruleset.typeChart().name(move.type(), language).toUpperCase(Locale.ROOT)),
                    "type-badge", typeStyle(move.type()));
            boolean active = option.user().equals(context.active());
            Label by = styled(new Label(user + (option.stab() ? "  ·  STAB" : "") + (active ? "  ·  OUT" : "")), "best-move-user");
            if (active) {
                by.getStyleClass().add("active-user");
                moveName.getStyleClass().add("active-move");
            }
            context.sprites().apply(option.user()).ifPresent(image -> by.setGraphic(miniSpriteView(image)));
            Label score = styled(new Label(String.valueOf(Math.round(option.score()))), "best-move-score");
            // In a half-width card (double battle) only the Pokémon column may shorten its text.
            moveName.setMinWidth(Region.USE_PREF_SIZE);
            type.setMinWidth(Region.USE_PREF_SIZE);
            score.setMinWidth(Region.USE_PREF_SIZE);
            by.setMinWidth(0);
            moves.addRow(rowIndex++, multiplier, moveName, type, by, score);
        }
        VBox section = new VBox(styled(new Label("YOUR BEST MOVES"), "section-title"));
        activeHint(hitting, activeBest, context).ifPresent(section.getChildren()::add);
        section.getChildren().add(moves);
        section.getStyleClass().addAll("matchup-section", "best-moves");
        return Optional.of(section);
    }

    /**
     * Whether the Pokémon on the field already has the best move: "Infernape is out: Close Combat is your best move."
     * or "Infernape is out: best Flamethrower (143). Garchomp hits harder: Earthquake (300)."
     */
    private static Optional<Label> activeHint(List<MoveOption> hitting, Optional<MoveOption> activeBest, Context context) {
        if (context.active() == null || hitting.isEmpty()) {
            return Optional.empty();
        }
        Ruleset ruleset = context.ruleset();
        Language language = context.language();
        String out = pokemonName(ruleset, context.active(), language) + " is out: ";
        MoveOption best = hitting.getFirst();
        String bestText = pokemonName(ruleset, best.user(), language) + " hits harder: " + moveName(ruleset, best, language)
                + " (" + Math.round(best.score()) + (best.caveat() != null ? ", if it has no " + best.caveat() : "") + ").";
        String text;
        boolean better;
        if (activeBest.isEmpty()) {
            text = out + "none of its moves hits. " + bestText;
            better = true;
        } else if (activeBest.get().score() >= best.score()) {
            text = out + moveName(ruleset, activeBest.get(), language) + " is your best move.";
            better = false;
        } else {
            text = out + "best " + moveName(ruleset, activeBest.get(), language) + " (" + Math.round(activeBest.get().score())
                    + "). " + bestText;
            better = true;
        }
        Label hint = styled(new Label(text), "active-hint", better ? "switch" : "stay");
        hint.setWrapText(true);
        return Optional.of(hint);
    }

    private static String pokemonName(Ruleset ruleset, PokemonId pokemon, Language language) {
        return ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()).name(language)).orElse("?");
    }

    private static String moveName(Ruleset ruleset, MoveOption option, Language language) {
        return ruleset.findMove(option.moveId()).map(move -> move.name(language)).orElse("?");
    }

    /**
     * "SAFEST SWITCH-INS": the team members that take the opponent's attacks of its own types best, with the
     * multiplier per type; the active Pokémon is always listed.
     */
    private static Optional<VBox> switchIns(List<EnemyAnalysis.SwitchIn> switchIns, Context context) {
        List<EnemyAnalysis.SwitchIn> shown = new ArrayList<>(switchIns.stream().limit(SWITCH_INS_SHOWN).toList());
        switchIns.stream().filter(switchIn -> switchIn.member().equals(context.active()) && !shown.contains(switchIn))
                .findFirst().ifPresent(shown::add);
        if (shown.isEmpty()) {
            return Optional.empty();
        }
        TypeChart chart = context.ruleset().typeChart();
        GridPane rows = new GridPane();
        rows.getStyleClass().add("switch-in-grid");
        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setHgrow(Priority.ALWAYS);
        rows.getColumnConstraints().add(nameColumn);
        int rowIndex = 0;
        for (EnemyAnalysis.SwitchIn switchIn : shown) {
            boolean active = switchIn.member().equals(context.active());
            Label name = styled(new Label(pokemonName(context.ruleset(), switchIn.member(), context.language())
                    + (active ? "  ·  OUT" : "")), "best-move-user");
            if (active) {
                name.getStyleClass().add("active-user");
            }
            name.setMinWidth(0);
            context.sprites().apply(switchIn.member()).ifPresent(image -> name.setGraphic(miniSpriteView(image)));
            rows.add(name, 0, rowIndex);
            int column = 1;
            for (Map.Entry<PokemonType, Double> taken : switchIn.taken().entrySet()) {
                Label multiplier = styled(new Label(format(taken.getValue())), "multiplier", multiplierStyle(taken.getValue()));
                multiplier.setMinWidth(Region.USE_PREF_SIZE);
                Label type = styled(new Label(chart.name(taken.getKey(), context.language()).toUpperCase(Locale.ROOT)),
                        "type-badge", typeStyle(taken.getKey()));
                type.setMinWidth(Region.USE_PREF_SIZE);
                rows.add(multiplier, column++, rowIndex);
                rows.add(type, column++, rowIndex);
            }
            rowIndex++;
        }
        Label title = styled(new Label("SAFEST SWITCH-INS"), "section-title");
        title.setTooltip(new Tooltip("How your team takes its attacks of its own types (its strongest, STAB moves): "
                + "the multiplier per type, safest Pokémon first"));
        VBox section = new VBox(title, rows);
        section.getStyleClass().addAll("matchup-section", "switch-ins");
        return Optional.of(section);
    }

    private static Label columnHeader(String text, String explanation) {
        Label header = styled(new Label(text), "column-header");
        header.setTooltip(new Tooltip(explanation));
        header.setMinWidth(Region.USE_PREF_SIZE);
        return header;
    }

    /** The in-game wording: super effective, not very effective, no effect. */
    private static List<Node> sections(Matchup matchup, TypeChart chart, Language language) {
        List<Node> sections = new ArrayList<>();
        addSection(sections, "SUPER EFFECTIVE", rows(matchup, multiplier -> multiplier > 1, chart, language));
        addSection(sections, "NOT VERY EFFECTIVE", rows(matchup, multiplier -> multiplier > 0 && multiplier < 1, chart, language));
        addSection(sections, "NO EFFECT", rows(matchup, multiplier -> multiplier == 0, chart, language));
        return sections;
    }

    private static void addSection(List<Node> sections, String title, List<Node> rows) {
        if (!rows.isEmpty()) {
            VBox section = new VBox(styled(new Label(title), "section-title"));
            section.getChildren().addAll(rows);
            section.getStyleClass().add("matchup-section");
            sections.add(section);
        }
    }

    private static List<Node> rows(Matchup matchup, DoublePredicate include, TypeChart chart, Language language) {
        List<Node> rows = new ArrayList<>();
        for (Map.Entry<Double, List<PokemonType>> group : matchup.byMultiplier().entrySet()) {
            if (include.test(group.getKey())) {
                Label multiplier = styled(new Label(format(group.getKey())), "multiplier", multiplierStyle(group.getKey()));
                multiplier.setMinWidth(Region.USE_PREF_SIZE);
                FlowPane types = badges(group.getValue(), chart, language);
                // An HBox asks for the height before the width is known; at the default wrap length of 400 px a row
                // of six badges that fits on one line got the height of two, and the multiplier sat below its badges.
                types.prefWrapLengthProperty().bind(types.widthProperty());
                HBox.setHgrow(types, Priority.ALWAYS);
                HBox row = new HBox(multiplier, types);
                row.getStyleClass().add("matchup-row");
                rows.add(row);
            }
        }
        return rows;
    }

    private static FlowPane badges(List<PokemonType> types, TypeChart chart, Language language) {
        FlowPane pane = new FlowPane();
        pane.getStyleClass().add("badges");
        for (PokemonType type : types) {
            pane.getChildren().add(styled(new Label(chart.name(type, language).toUpperCase(Locale.ROOT)),
                    "type-badge", typeStyle(type)));
        }
        return pane;
    }

    /** Style class and looked-up colour name of a type, e.g. "type-fire". */
    private static String typeStyle(PokemonType type) {
        return "type-" + type.name().toLowerCase(Locale.ROOT);
    }

    /** Fractions as the games write them: "4×", "½×", "¼×". */
    static String format(double multiplier) {
        if (multiplier == 0.5) {
            return "½×";
        }
        if (multiplier == 0.25) {
            return "¼×";
        }
        return BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString() + "×";
    }

    private static String multiplierStyle(double multiplier) {
        if (multiplier == 0) {
            return "immune";
        }
        if (multiplier == 1) {
            return "neutral";
        }
        if (multiplier > 1) {
            return multiplier >= 4 ? "weak-4" : "weak";
        }
        return multiplier >= 0.5 ? "resist" : "resist-4";
    }

    private static <T extends Control> T styled(T control, String... styleClasses) {
        control.getStyleClass().addAll(styleClasses);
        return control;
    }
}
