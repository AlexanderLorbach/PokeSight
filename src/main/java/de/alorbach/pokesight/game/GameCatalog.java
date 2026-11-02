package de.alorbach.pokesight.game;

import de.alorbach.pokesight.YamlResources;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** The games listed in {@code games/index.yaml}: the official games, then the ROM hacks. */
public final class GameCatalog {

    private GameCatalog() {
    }

    public static List<GameDefinition> load() {
        Index index = YamlResources.read("games/index.yaml", Index.class);
        return Stream.concat(index.games().stream().map(id -> game(id, false)), index.romHacks().stream().map(id -> game(id, true)))
                .toList();
    }

    private static GameDefinition game(String id, boolean romHack) {
        return YamlResources.read("games/" + id + ".yaml", GameDefinition.class).withId(id, romHack);
    }

    record Index(List<String> games, List<String> romHacks) {
        Index {
            Objects.requireNonNull(games, "games");
            romHacks = romHacks == null ? List.of() : List.copyOf(romHacks);
        }
    }
}
