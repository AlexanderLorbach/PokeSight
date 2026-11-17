package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.catching.CatchAdvice;
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
import javafx.scene.control.ContentDisplay;
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
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One opposing Pokémon: sprite, name, types, the team's best moves against it, the best switch-in and which attack
 * types are (not) effective. With the player's active Pokémon known (two in a double battle), their moves are marked,
 * the best one in bold, and a warning says to switch out one that takes 2× or more. The
 * compact variant (borderless mode) drops the buttons and notes; {@link FitPane} scales it to fill.
 */
final class PokemonCard extends VBox {

    private static final double SPRITE_SIZE = 80; // sprites are 56 (Gen 1) to 96 px (Gen 5 and later); scaled to this
    private static final double MINI_SPRITE_SIZE = 28;
    private static final int BEST_MOVES_SHOWN = 3;
    private static final int SWITCH_INS_SHOWN = 3;
    /** Style class of the TYPE column of Your best moves, which a narrow card hides. */
    private static final String BEST_MOVE_TYPE = "best-move-type";

    /**
     * What every card of a battle view shares.
     *
     * @param terastal opponents can terastallize (Scarlet/Violet): cards offer a Tera type picker
     * @param active   the player's Pokémon on the field that are known and part of the team (two in a double
     *                 battle); empty if none
     * @param catching which Pokémon get the catch star
     */
    record Context(Ruleset ruleset, Language language, List<SearchField.Option<PokemonId>> pokemonOptions, boolean teamSet,
                   Function<PokemonId, Optional<Image>> sprites, boolean terastal, List<PokemonId> active,
                   CatchAdvice catching) {
        Context {
            active = List.copyOf(active);
            Objects.requireNonNull(catching, "catching");
        }

        boolean isOut(PokemonId pokemon) {
            return active.contains(pokemon);
        }
    }

    private final HBox header;
    /** Min width of Your best moves with its TYPE column, measured while it shows; 0 until then. */
    private double bestMovesWidthWithType;
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
        name.setMinWidth(0);
        HBox nameRow = new HBox(6, name);
        nameRow.setAlignment(Pos.BASELINE_LEFT);
        if (analysis.isPresent() && entry.pokemon() != null) {
            context.catching().reason(entry.pokemon().speciesId()).ifPresent(reason -> nameRow.getChildren().add(catchStar(reason)));
        }
        VBox titles = new VBox(4, nameRow);
        analysis.ifPresent(enemy -> {
            FlowPane types = badges(enemy.types(), chart, language);
            if (enemy.tera()) {
                Label tera = styled(new Label("TERA"), "tera-badge");
                tera.setTooltip(new Tooltip("Terastallized: it has this type alone now"));
                types.getChildren().addFirst(tera);
            }
            titles.getChildren().add(types);
            // Under its types: in a double battle each card shows whether this opponent counters your Pokémon.
            // Also borderless: the one thing not to miss.
            titles.getChildren().addAll(dangerWarnings(enemy, context));
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
            bestMoves(enemy.bestMoves(), context, compact).ifPresent(getChildren()::add);
            widthProperty().addListener((obs, old, width) -> fitBestMoves(width.doubleValue()));
            bestSwitchIns(enemy.switchIns(), context, compact).ifPresent(getChildren()::add);
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

    /** "★" next to the name of a Pokémon worth catching; hovering it tells why. */
    private static Label catchStar(String reason) {
        Label star = styled(new Label("★"), "catch-star");
        star.setMinWidth(Region.USE_PREF_SIZE); // a long name is cut, never the star
        Tooltip tooltip = new Tooltip("Worth catching\n" + reason);
        tooltip.setShowDelay(Duration.millis(150));
        star.setTooltip(tooltip);
        return star;
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

    /**
     * "YOUR BEST MOVES": the strongest moves of the team that deal damage at all. With the active Pokémon known, the
     * moves of each are marked and its best move is always listed, in bold.
     */
    private static Optional<VBox> bestMoves(List<MoveOption> options, Context context, boolean compact) {
        List<MoveOption> shown = new ArrayList<>(options.stream().filter(option -> option.score() > 0)
                .limit(BEST_MOVES_SHOWN).toList());
        Set<MoveOption> outBests = context.active().stream()
                .flatMap(active -> outBest(options, active, context.ruleset()).stream())
                .collect(Collectors.toSet());
        outBests.stream().filter(option -> !shown.contains(option)).forEach(shown::add);
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
                styled(columnHeader("TYPE", "The move's type"), BEST_MOVE_TYPE),
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
            Label multiplier = styled(new Label(format(option.effectiveness())), "multiplier", multiplierStyle(option.effectiveness()));
            multiplier.setMinWidth(Region.USE_PREF_SIZE);
            Label moveName = styled(new Label(move.name(language)), "best-move-name", SearchField.moveTypeStyle(move.type()));
            moveName.setTooltip(new Tooltip(ruleset.typeChart().name(move.type(), language) + " · "
                    + SearchField.categoryName(move.category()) + " · power " + move.power()));
            Label type = styled(new Label(ruleset.typeChart().name(move.type(), language).toUpperCase(Locale.ROOT)),
                    "type-badge", typeStyle(move.type()), BEST_MOVE_TYPE);
            boolean active = context.isOut(option.user());
            Label by = styled(new Label(user + (option.stab() ? "  ·  STAB" : "")), "best-move-user");
            if (active) {
                by.getStyleClass().add("active-user");
            }
            if (outBests.contains(option)) {
                moveName.getStyleClass().add("out-best-move");
            }
            Node byGraphic = userGraphic(option.user(), context);
            by.setGraphic(byGraphic);
            Label score = styled(new Label(String.valueOf(Math.round(option.score()))), "best-move-score");
            // In a half-width card (double battle) only the Pokémon column may shorten its text.
            moveName.setMinWidth(Region.USE_PREF_SIZE);
            type.setMinWidth(Region.USE_PREF_SIZE);
            score.setMinWidth(Region.USE_PREF_SIZE);
            by.setMinWidth(minWidthWithGraphic(by, byGraphic)); // only the name shortens, never over the score
            showNameOnlyIfItFits(by);
            by.setTooltip(new Tooltip(by.getText()));
            moves.addRow(rowIndex++, multiplier, moveName, type, by, score);
        }
        VBox section = new VBox(styled(new Label("YOUR BEST MOVES"), "section-title"), moves);
        section.getStyleClass().addAll("matchup-section", "best-moves");
        return Optional.of(section);
    }

    /**
     * Hides the TYPE column of Your best moves when the card is too narrow for all columns (half-width card in a
     * double battle, small window): the move's type still shows as its colour bar. Wider again, it comes back.
     */
    private void fitBestMoves(double cardWidth) {
        if (!(lookup(".best-moves-grid") instanceof GridPane grid) || !(grid.getParent() instanceof Region section)) {
            return;
        }
        List<Node> typeColumn = grid.getChildren().stream().filter(node -> node.getStyleClass().contains(BEST_MOVE_TYPE)).toList();
        boolean shown = typeColumn.stream().allMatch(Node::isManaged);
        if (shown) {
            bestMovesWidthWithType = grid.minWidth(-1);
        }
        double available = cardWidth - snappedLeftInset() - snappedRightInset() - section.snappedLeftInset()
                - section.snappedRightInset();
        boolean show = available >= bestMovesWidthWithType;
        if (show != shown) {
            typeColumn.forEach(node -> {
                node.setVisible(show);
                node.setManaged(show);
            });
        }
    }

    /**
     * The best move of a Pokémon on the field, so it is always listed: one that does not hit (score 0) if it has
     * nothing better, an attack the opponent is immune to before a status move (its "0×" says why it does not hit).
     */
    private static Optional<MoveOption> outBest(List<MoveOption> options, PokemonId active, Ruleset ruleset) {
        Comparator<MoveOption> best = Comparator.comparingDouble(MoveOption::score)
                .thenComparing(option -> ruleset.findMove(option.moveId()).map(move -> move.power() > 0).orElse(false));
        return options.stream().filter(option -> option.user().equals(active)).max(best);
    }

    /** In front of one of your Pokémon's names: its mini sprite (an empty box if there is none). */
    private static Node userGraphic(PokemonId pokemon, Context context) {
        HBox graphic = new HBox();
        graphic.setAlignment(Pos.CENTER_LEFT);
        context.sprites().apply(pokemon).ifPresent(image -> graphic.getChildren().add(miniSpriteView(image)));
        return graphic;
    }

    /** A label's graphic has a fixed size (the sprite): its width is known before CSS and the label keeps it. */
    private static double minWidthWithGraphic(Label label, Node graphic) {
        return graphic.prefWidth(-1) + label.getGraphicTextGap();
    }

    /**
     * In a half-width card the Pokémon column may be too narrow for the name: then only the sprite shows
     * (the name is in the tooltip), not a fragment like "St…".
     */
    private static void showNameOnlyIfItFits(Label label) {
        label.widthProperty().addListener((obs, old, width) -> {
            Text text = new Text(label.getText());
            text.setFont(label.getFont());
            double full = label.getGraphic().prefWidth(-1) + label.getGraphicTextGap() + text.getLayoutBounds().getWidth()
                    + label.snappedLeftInset() + label.snappedRightInset();
            label.setContentDisplay(width.doubleValue() + 0.5 < full ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        });
    }

    private static String pokemonName(Ruleset ruleset, PokemonId pokemon, Language language) {
        return ruleset.findSpecies(pokemon.speciesId()).map(species -> species.form(pokemon.form()).name(language)).orElse("?");
    }

    /**
     * "BEST SWITCH-INS": the {@value #SWITCH_INS_SHOWN} team members that deal the most damage for the damage they take,
     * best first, each with its best move and how it takes the opponent's own types. One on the field has a green name.
     */
    private static Optional<VBox> bestSwitchIns(List<EnemyAnalysis.SwitchIn> switchIns, Context context, boolean compact) {
        if (switchIns.isEmpty()) {
            return Optional.empty();
        }
        Ruleset ruleset = context.ruleset();
        Language language = context.language();
        GridPane rows = new GridPane();
        rows.getStyleClass().add("switch-in-grid");
        if (compact) {
            rows.setMinWidth(Region.USE_PREF_SIZE); // FitPane scales the whole card instead of cutting off names
        }
        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setHgrow(Priority.ALWAYS);
        rows.getColumnConstraints().addAll(nameColumn, new ColumnConstraints(), new ColumnConstraints());
        rows.add(columnHeader("POKÉMON", "Best switch-in first"), 0, 0);
        rows.add(columnHeader("BEST MOVE", "Its strongest move against this Pokémon and its type effectiveness"), 1, 0, 2, 1);
        rows.add(columnHeader("TAKES", "The most it takes from the opponent's attacks of its own types (its STAB moves); "
                + "hover a value for each type"), 3, 0);
        int rowIndex = 1;
        for (EnemyAnalysis.SwitchIn switchIn : switchIns.stream().limit(SWITCH_INS_SHOWN).toList()) {
            boolean out = context.isOut(switchIn.member());
            Label name = styled(new Label(rowIndex + ". " + pokemonName(ruleset, switchIn.member(), language)),
                    "best-move-user", "switch-in-name");
            if (out) {
                name.getStyleClass().add("active-user");
            }
            Node nameGraphic = userGraphic(switchIn.member(), context);
            name.setGraphic(nameGraphic);
            name.setMinWidth(minWidthWithGraphic(name, nameGraphic));
            rows.add(name, 0, rowIndex);
            if (switchIn.bestMove().isPresent()) {
                MoveOption move = switchIn.bestMove().get();
                Label multiplier = styled(new Label(format(move.effectiveness())), "multiplier", multiplierStyle(move.effectiveness()));
                Move found = ruleset.findMove(move.moveId()).orElseThrow();
                Label moveName = styled(new Label(found.name(language)), "best-move-name", SearchField.moveTypeStyle(found.type()));
                multiplier.setMinWidth(Region.USE_PREF_SIZE);
                moveName.setMinWidth(Region.USE_PREF_SIZE);
                rows.add(multiplier, 1, rowIndex);
                rows.add(moveName, 2, rowIndex);
            } else {
                Label none = styled(new Label("no hitting move"), "switch-in-label");
                none.setMinWidth(Region.USE_PREF_SIZE);
                rows.add(none, 1, rowIndex, 2, 1);
            }
            // The worst multiplier only, so three rows fit a half-width card; the tooltip has it per type.
            Label taken = styled(new Label(format(switchIn.worst())), "multiplier", multiplierStyle(switchIn.worst()));
            taken.setMinWidth(Region.USE_PREF_SIZE);
            Tooltip perType = new Tooltip(switchIn.taken().entrySet().stream()
                    .map(entry -> ruleset.typeChart().name(entry.getKey(), language) + " " + format(entry.getValue()))
                    .collect(Collectors.joining("\n")));
            perType.setShowDelay(Duration.millis(150));
            taken.setTooltip(perType);
            rows.add(taken, 3, rowIndex);
            rowIndex++;
        }

        Label title = styled(new Label("BEST SWITCH-INS"), "section-title");
        title.setTooltip(new Tooltip("Your team members that deal the most damage for the damage they take: the score of "
                + "the best move / (the worst multiplier against the opponent's own types × the opponent's Attack / the "
                + "Defense or Sp. Atk / Sp. Def, whichever is worse). Base stats: levels are not known"));
        VBox section = new VBox(title, rows);
        section.getStyleClass().addAll("matchup-section", "switch-ins");
        return Optional.of(section);
    }

    /**
     * A warning per Pokémon on the field that takes 2× or more from the opponent's own types: "⚠ Kirlia takes 2× from
     * GHOST". From 4× on (likely knocked out in one hit) it stands out more; whom to switch to is in the best switch-ins.
     */
    private static List<Label> dangerWarnings(EnemyAnalysis enemy, Context context) {
        List<Label> warnings = new ArrayList<>();
        Ruleset ruleset = context.ruleset();
        Language language = context.language();
        for (PokemonId active : context.active()) {
            enemy.switchIn(active).filter(switchIn -> !switchIn.dangerTypes().isEmpty()).ifPresent(switchIn -> {
                String types = switchIn.dangerTypes().stream()
                        .map(type -> ruleset.typeChart().name(type, language).toUpperCase(Locale.ROOT))
                        .collect(Collectors.joining(" and "));
                boolean oneHit = switchIn.worst() >= EnemyAnalysis.ONE_HIT;
                String text = "⚠ " + pokemonName(ruleset, active, language) + " takes " + format(switchIn.worst()) + " from " + types;
                Label warning = styled(new Label(text), "danger-hint", oneHit ? "one-hit" : "weak");
                warning.setWrapText(true);
                warning.setMinHeight(Region.USE_PREF_SIZE); // wraps instead of cutting the text off with "..."
                warnings.add(warning);
            });
        }
        return warnings;
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

    /** Type badges as the games colour them: "FIRE" on orange (also used by the team editor). */
    static FlowPane badges(List<PokemonType> types, TypeChart chart, Language language) {
        FlowPane pane = new FlowPane();
        pane.getStyleClass().add("badges");
        for (PokemonType type : types) {
            pane.getChildren().add(typeBadge(type, chart, language));
        }
        return pane;
    }

    static Label typeBadge(PokemonType type, TypeChart chart, Language language) {
        Label badge = styled(new Label(chart.name(type, language).toUpperCase(Locale.ROOT)), "type-badge", typeStyle(type));
        badge.setMinWidth(Region.USE_PREF_SIZE); // never "FIG..."
        return badge;
    }

    /** Style class and looked-up colour name of a type, e.g. "type-fire". */
    static String typeStyle(PokemonType type) {
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
