package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.NameMatcher;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.TypeChart;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Text field with name suggestions (Pokémon, moves). In action mode a pick is reported and the field is cleared
 * (add / change a Pokémon); in selection mode the picked name stays and {@link #selected()} returns its value.
 */
final class SearchField<T> extends TextField {

    private static final int MAX_SUGGESTIONS = 10;

    /**
     * @param detail extra text shown in the suggestion list only (e.g. a move's type and power); may be empty
     * @param key    normalized label used for searching
     */
    record Option<T>(String label, String detail, T value, String key) {
        static <T> Option<T> of(String label, String detail, T value) {
            return new Option<>(label, detail, value, NameMatcher.normalize(label));
        }
    }

    private final List<Option<T>> options;
    private final boolean keepSelection;
    private final Consumer<T> onPick;
    private final ContextMenu suggestions = new ContextMenu();
    private Option<T> selected;
    private boolean settingText;

    SearchField(List<Option<T>> options, String prompt, boolean keepSelection, Consumer<T> onPick) {
        this.options = List.copyOf(options);
        this.keepSelection = keepSelection;
        this.onPick = onPick;
        setPromptText(prompt);
        getStyleClass().add("search-field");
        textProperty().addListener((obs, old, text) -> {
            if (settingText) {
                return;
            }
            if (selected != null && !selected.label().equals(text)) {
                selected = null; // edited after picking: no longer a valid choice
            }
            showSuggestions(text);
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                matches(getText()).stream().findFirst().ifPresent(this::pick);
            } else if (event.getCode() == KeyCode.ESCAPE) {
                setText("");
            }
        });
        focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) {
                suggestions.hide();
            }
        });
    }

    /** All species and alternate forms of the ruleset, named in the given language. */
    static List<Option<PokemonId>> pokemon(Ruleset ruleset, Language language) {
        List<Option<PokemonId>> options = new ArrayList<>();
        ruleset.species().forEach((speciesId, species) -> {
            options.add(Option.of(species.name(language), "", new PokemonId(speciesId, 0)));
            species.forms().forEach((form, details) -> options.add(Option.of(details.name(language), "", new PokemonId(speciesId, form))));
        });
        options.sort(Comparator.comparing(Option::label));
        return options;
    }

    /** All damaging moves of the ruleset; the suggestions show type and power. */
    static List<Option<Integer>> moves(Ruleset ruleset, Language language) {
        TypeChart chart = ruleset.typeChart();
        List<Option<Integer>> options = new ArrayList<>();
        ruleset.moves().forEach((moveId, move) -> options.add(Option.of(move.name(language),
                chart.name(move.type(), language) + " · " + move.power(), moveId)));
        options.sort(Comparator.comparing(Option::label));
        return options;
    }

    Optional<T> selected() {
        return Optional.ofNullable(selected).map(Option::value);
    }

    /** Shows the option with this value as the current choice (selection mode), or clears the field for null. */
    void select(T value) {
        selected = value == null ? null : options.stream().filter(option -> Objects.equals(option.value(), value)).findFirst().orElse(null);
        settingText = true;
        setText(selected == null ? "" : selected.label());
        settingText = false;
    }

    /** Names starting with the input first, then names containing it. */
    List<Option<T>> matches(String text) {
        String key = NameMatcher.normalize(text);
        if (key.isEmpty()) {
            return List.of();
        }
        List<Option<T>> result = new ArrayList<>(options.stream().filter(option -> option.key().startsWith(key)).toList());
        options.stream().filter(option -> !option.key().startsWith(key) && option.key().contains(key)).forEach(result::add);
        return result.subList(0, Math.min(MAX_SUGGESTIONS, result.size()));
    }

    private void showSuggestions(String text) {
        List<Option<T>> matches = matches(text);
        if (matches.isEmpty()) {
            suggestions.hide();
            return;
        }
        suggestions.getItems().setAll(matches.stream().map(option -> {
            MenuItem item = new MenuItem(option.detail().isEmpty() ? option.label() : option.label() + "   ·   " + option.detail());
            item.setOnAction(event -> pick(option));
            return item;
        }).toList());
        if (!suggestions.isShowing() && getScene() != null) {
            suggestions.show(this, Side.BOTTOM, 0, 0);
        }
    }

    private void pick(Option<T> option) {
        suggestions.hide();
        if (keepSelection) {
            select(option.value());
        } else {
            settingText = true;
            setText("");
            settingText = false;
        }
        onPick.accept(option.value());
    }
}
