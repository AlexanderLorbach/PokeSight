package de.alorbach.pokesight.game;

import de.alorbach.pokesight.YamlResources;

import java.util.List;
import java.util.Objects;

/** The games listed in {@code games/index.yaml}. */
public final class GameCatalog {

    private GameCatalog() {
    }

    public static List<GameDefinition> load() {
        return YamlResources.read("games/index.yaml", Index.class).games().stream()
                .map(id -> YamlResources.read("games/" + id + ".yaml", GameDefinition.class).withId(id))
                .toList();
    }

    record Index(List<String> games) {
        Index {
            Objects.requireNonNull(games, "games");
        }
    }
}
