package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.matchup.MovePool;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Learnset;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.TypeChart;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The game's learnsets as a searchable table: which damaging moves a Pokémon learns and how (level, TM, tutor, egg).
 * Shows the team's Pokémon by default, or every Pokémon of the game. The search matches every word against the
 * Pokémon, the move, its type and category and how it is learned ("ground infernape", "tm26").
 */
final class LearnableMovesView {

    private static final double WIDTH = 760;
    private static final double HEIGHT = 560;
    private static final double MINI_SPRITE_SIZE = 24;
    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern WORDS = Pattern.compile("\\s+");

    /**
     * One table row: a learnable move with its texts, and its search text (lowercase, without accents).
     *
     * @param order the Pokémon's place in the list (team order or dex order), so sorting by Pokémon keeps it
     */
    record Entry(MovePool.Learnable learnable, int order, String pokemon, String move, String how, String key) {
    }

    /** The view's parts that change after it is built. */
    private record Parts(BorderPane root, TextField search, Runnable reload) {
    }

    private final Stage stage = new Stage();
    private final Parts parts;

    private LearnableMovesView(Window owner, Parts parts) {
        this.parts = parts;
        Scene scene = new Scene(parts.root(), WIDTH, HEIGHT);
        Theme.apply(scene);
        stage.initOwner(owner); // closes with the team editor
        stage.setTitle("PokeSight - learnable moves");
        Theme.decorate(stage);
        stage.setScene(scene);
    }

    /**
     * The list for one team editor; {@link #show} opens it.
     *
     * @param team the team as entered in the editor, read again whenever the list is shown or switches to it
     */
    static LearnableMovesView of(Window owner, MovePool pool, Ruleset ruleset, Language language, SpriteLibrary sprites,
                                 Supplier<Team> team) {
        return new LearnableMovesView(owner, build(pool, ruleset, language, sprites, team, ""));
    }

    /** Shows the list (brought to the front if it is open) with the team as it is now and this search. */
    void show(String query) {
        parts.reload().run();
        parts.search().setText(query);
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            stage.show();
        }
    }

    /** The list's content alone, e.g. to render it. */
    static BorderPane content(MovePool pool, Ruleset ruleset, Language language, SpriteLibrary sprites,
                              Supplier<Team> team, String query) {
        return build(pool, ruleset, language, sprites, team, query).root();
    }

    private static Parts build(MovePool pool, Ruleset ruleset, Language language, SpriteLibrary sprites,
                               Supplier<Team> team, String query) {
        TextField search = new TextField(query);
        search.setPromptText("Move, type, Pokémon or TM…");
        search.getStyleClass().add("learnable-search");
        HBox.setHgrow(search, Priority.ALWAYS);
        Label count = new Label();
        count.getStyleClass().add("coverage-label");
        count.setMinWidth(Label.USE_PREF_SIZE);
        HBox bar = new HBox(8, search, count);
        bar.setAlignment(Pos.CENTER_LEFT);

        ObservableList<Entry> entries = FXCollections.observableArrayList();
        FilteredList<Entry> filtered = new FilteredList<>(entries);
        SortedList<Entry> sorted = new SortedList<>(filtered);
        TableView<Entry> table = table(ruleset.typeChart(), language, pool, sprites);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setPlaceholder(placeholder("No damaging move matches"));

        ToggleButton teamOnly = new ToggleButton("Your team");
        teamOnly.getStyleClass().addAll("ghost", "small");
        teamOnly.setTooltip(new Tooltip("Only your team's Pokémon; off: every Pokémon of the game"));
        Runnable refilter = () -> {
            filtered.setPredicate(matching(search.getText()));
            count.setText(filtered.size() + " moves");
        };
        Runnable reload = () -> {
            List<PokemonId> pokemon = teamOnly.isSelected()
                    ? team.get().members().stream().map(Team.Member::pokemon).distinct().toList()
                    : null;
            List<MovePool.Learnable> learnables = pokemon == null ? pool.all()
                    : pokemon.stream().flatMap(id -> pool.of(id).stream()).toList();
            Map<PokemonId, Integer> order = new HashMap<>(); // team order, or dex order for every Pokémon
            learnables.forEach(learnable -> order.putIfAbsent(learnable.pokemon(), order.size()));
            entries.setAll(learnables.stream()
                    .map(learnable -> entry(learnable, order.get(learnable.pokemon()), ruleset, language, pool))
                    .toList());
            table.setPlaceholder(placeholder(teamOnly.isSelected() && pokemon.isEmpty()
                    ? "Your team is empty: turn off \"Your team\" to see every Pokémon" : "No damaging move matches"));
            refilter.run();
        };
        teamOnly.setSelected(!team.get().members().isEmpty());
        teamOnly.selectedProperty().addListener((obs, old, selected) -> reload.run());
        search.textProperty().addListener((obs, old, text) -> refilter.run());
        reload.run();

        VBox body = new VBox(8, bar, table);
        body.getStyleClass().add("learnable-moves");
        VBox.setVgrow(table, Priority.ALWAYS);
        BorderPane root = new BorderPane(body);
        root.setTop(TitleBar.of("Learnable moves", null, teamOnly));
        return new Parts(root, search, reload);
    }

    private static TableView<Entry> table(TypeChart chart, Language language, MovePool pool, SpriteLibrary sprites) {
        TableView<Entry> table = new TableView<>();
        table.getStyleClass().add("learnable-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Entry, Entry> pokemon = column("Pokémon", 170, Function.identity());
        pokemon.setComparator(Comparator.comparingInt(Entry::order));
        pokemon.setCellFactory(column -> cell(entry -> {
            Label name = new Label(entry.pokemon());
            sprites.image(entry.learnable().pokemon()).ifPresent(image -> {
                ImageView view = new ImageView(image);
                view.setFitWidth(MINI_SPRITE_SIZE);
                view.setPreserveRatio(true);
                name.setGraphic(view);
            });
            name.getStyleClass().add("member-chip");
            return name;
        }));

        TableColumn<Entry, Entry> move = column("Move", 190, Function.identity());
        move.setComparator(Comparator.comparing(Entry::move));
        move.setCellFactory(column -> cell(entry -> {
            Label name = new Label(entry.move());
            name.getStyleClass().addAll("type-badge", PokemonCard.typeStyle(entry.learnable().move().type()));
            HBox box = new HBox(4, CategoryIcon.ofMove(entry.learnable().move().category()), name);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }));

        TableColumn<Entry, Entry> type = column("Type", 90, Function.identity());
        type.setComparator(Comparator.comparing((Entry entry) -> entry.learnable().move().type()));
        type.setCellFactory(column -> cell(entry -> PokemonCard.typeBadge(entry.learnable().move().type(), chart, language)));

        TableColumn<Entry, Integer> power = column("Power", 64, entry -> entry.learnable().move().power());
        power.getStyleClass().add("learnable-number");
        power.setSortType(TableColumn.SortType.DESCENDING);

        TableColumn<Entry, Entry> how = column("Learned", 130, Function.identity());
        how.setComparator(Comparator.comparing((Entry entry) -> entry.learnable().ways().getFirst().method())
                .thenComparingInt(entry -> entry.learnable().ways().getFirst().level()));
        how.setCellFactory(column -> cell(entry -> {
            Label label = new Label(entry.how());
            label.getStyleClass().add("learnable-how");
            if (entry.learnable().eggOnly()) {
                label.getStyleClass().add("egg-only"); // a Pokémon already caught cannot learn it
                label.setTooltip(new Tooltip("Only by breeding: hatched Pokémon learn it"));
            } else if (!entry.learnable().source().equals(pool.gameLearnsets())) {
                label.getStyleClass().add("newer-game"); // a Pokémon the game itself does not have
                label.setTooltip(new Tooltip("As in the newer games: the game's own data lacks this Pokémon"));
            }
            return label;
        }));

        table.getColumns().setAll(List.of(pokemon, move, type, power, how));
        table.getSortOrder().setAll(List.of(pokemon, power));
        return table;
    }

    private static <V> TableColumn<Entry, V> column(String title, double width, Function<Entry, V> value) {
        TableColumn<Entry, V> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setReorderable(false);
        column.setCellValueFactory(features -> new ReadOnlyObjectWrapper<>(value.apply(features.getValue())));
        return column;
    }

    private static TableCell<Entry, Entry> cell(Function<Entry, Node> graphic) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(empty || entry == null ? null : graphic.apply(entry));
            }
        };
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        return label;
    }

    static Entry entry(MovePool.Learnable learnable, int order, Ruleset ruleset, Language language, MovePool pool) {
        PokemonId id = learnable.pokemon();
        String pokemon = ruleset.findSpecies(id.speciesId()).map(species -> species.form(id.form()).name(language)).orElse("?");
        Move move = learnable.move();
        String how = how(learnable, pool);
        String key = searchable(String.join(" ", pokemon, move.name(language), ruleset.typeChart().name(move.type(), language),
                SearchField.categoryName(move.category()), how));
        return new Entry(learnable, order, pokemon, move.name(language), how, key);
    }

    /** "Lv 36 · TM26": every way to learn it, easiest first. */
    static String how(MovePool.Learnable learnable, MovePool pool) {
        return String.join(" · ", learnable.ways().stream().map(way -> wayText(way, learnable.moveId(), pool)).toList());
    }

    /** The easiest way to learn it: "Lv 36", "TM26", "Tutor". */
    static String easiest(MovePool.Learnable learnable, MovePool pool) {
        return wayText(learnable.ways().getFirst(), learnable.moveId(), pool);
    }

    /** A machine the game has no number for (a Pokémon from a newer game, a move no TM of the game teaches): "TM". */
    private static String wayText(Learnset.Way way, int moveId, MovePool pool) {
        return switch (way.method()) {
            case LEVEL -> way.level() == 0 ? "Evolve" : "Lv " + way.level();
            case MACHINE -> pool.machine(moveId).orElse("TM");
            case TUTOR -> "Tutor";
            case REMINDER -> "Reminder";
            case EGG -> "Egg";
        };
    }

    /** Every word of the query is in the row's search text. */
    static Predicate<Entry> matching(String query) {
        List<String> words = Arrays.stream(WORDS.split(searchable(query).strip())).filter(word -> !word.isEmpty()).toList();
        return entry -> words.stream().allMatch(entry.key()::contains);
    }

    private static String searchable(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
