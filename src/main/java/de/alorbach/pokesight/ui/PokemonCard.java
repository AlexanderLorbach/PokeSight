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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
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
 * One opposing Pokémon: sprite, name, types, the team's best moves against it and which attack types are (not)
 * effective. The compact variant (borderless mode) drops the buttons and notes; {@link FitPane} scales it to fill.
 */
final class PokemonCard extends VBox {

    private static final double SPRITE_SIZE = 80; // Gen 4 sprites are 80 x 80
    private static final double MINI_SPRITE_SIZE = 28;
    private static final int BEST_MOVES_SHOWN = 3;

    /** What every card of a battle view shares. */
    record Context(Ruleset ruleset, Language language, List<SearchField.Option<PokemonId>> pokemonOptions, boolean teamSet,
                   Function<PokemonId, Optional<Image>> sprites) {
    }

    private final HBox header;
    private SearchField<PokemonId> search;

    /**
     * @param analysis empty if the scan could not recognize the name
     * @param onRemove null for scanned Pokémon, which disappear with the battle
     */
    PokemonCard(BattleBoard.Entry entry, Optional<EnemyAnalysis> analysis, Optional<Image> sprite, Context context,
                boolean compact, Consumer<PokemonId> onChange, Runnable onRemove) {
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
        entry.level().ifPresent(level -> titles.getChildren().add(styled(new Label("Lv. " + level), "pokemon-meta")));
        analysis.ifPresent(enemy -> titles.getChildren().add(badges(enemy.types(), chart, language)));
        HBox.setHgrow(titles, Priority.ALWAYS);
        titles.setMinWidth(0);
        header = new HBox(spriteFrame, titles);
        header.getStyleClass().add("card-header");
        header.setAlignment(Pos.CENTER_LEFT);
        if (!compact) {
            header.getChildren().add(actions(context, onChange, onRemove));
        }
        getChildren().add(header);

        if (!compact && !entry.readText().isEmpty() && (entry.corrected() || analysis.isEmpty())) {
            String note = entry.corrected() ? " (corrected)" : " - use Change to pick the right Pokémon";
            getChildren().add(styled(new Label("Read: \"" + entry.readText() + "\"" + note), "read-text"));
        }
        analysis.ifPresent(enemy -> {
            bestMoves(enemy.bestMoves(), context, compact).ifPresent(getChildren()::add);
            if (!context.teamSet() && !compact) {
                Label hint = styled(new Label("Set your team to see which of your moves hits hardest."), "read-text");
                hint.setWrapText(true);
                getChildren().add(hint);
            }
            getChildren().addAll(sections(enemy.matchup(), chart, language));
        });
    }

    private VBox actions(Context context, Consumer<PokemonId> onChange, Runnable onRemove) {
        Button change = styled(new Button("Change"), "ghost", "small");
        change.setOnAction(event -> toggleSearch(context.pokemonOptions(), onChange));
        VBox actions = new VBox(6, change);
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
     * A small sprite cropped to the Pokémon itself: Gen 4 sprites leave much of their 80 x 80 frame empty, which would
     * make small Pokémon a few pixels wide at this size.
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

    /** "YOUR BEST MOVES": the strongest moves of the team that deal damage at all. */
    private static Optional<VBox> bestMoves(List<MoveOption> options, Context context, boolean compact) {
        List<MoveOption> shown = options.stream().filter(option -> option.score() > 0).limit(BEST_MOVES_SHOWN).toList();
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
                columnHeader("SCORE", "Power × effectiveness × 1.5 with STAB. Ranks the moves; not the real damage"));

        Ruleset ruleset = context.ruleset();
        Language language = context.language();
        int rowIndex = 1;
        for (MoveOption option : shown) {
            Move move = ruleset.findMove(option.moveId()).orElseThrow();
            String user = ruleset.findSpecies(option.user().speciesId())
                    .map(species -> species.form(option.user().form()).name(language)).orElse("?");
            Label multiplier = styled(new Label(format(option.effectiveness())), "multiplier", multiplierStyle(option.effectiveness()));
            multiplier.setMinWidth(Region.USE_PREF_SIZE);
            Label moveName = styled(new Label(move.name(language)), "best-move-name");
            Label type = styled(new Label(ruleset.typeChart().name(move.type(), language).toUpperCase(Locale.ROOT)),
                    "type-badge", typeStyle(move.type()));
            Label by = styled(new Label(user + (option.stab() ? "  ·  STAB" : "")), "best-move-user");
            context.sprites().apply(option.user()).ifPresent(image -> by.setGraphic(miniSpriteView(image)));
            Label score = styled(new Label(String.valueOf(Math.round(option.score()))), "best-move-score");
            moves.addRow(rowIndex++, multiplier, moveName, type, by, score);
        }
        VBox section = new VBox(styled(new Label("YOUR BEST MOVES"), "section-title"), moves);
        section.getStyleClass().addAll("matchup-section", "best-moves");
        return Optional.of(section);
    }

    private static Label columnHeader(String text, String explanation) {
        Label header = styled(new Label(text), "column-header");
        header.setTooltip(new Tooltip(explanation));
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
