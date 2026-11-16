package de.alorbach.pokesight.game;

import de.alorbach.pokesight.YamlResources;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** The games listed in {@code games/index.yaml}: the official games, then the ROM hacks, then custom games. */
public final class GameCatalog {

    private GameCatalog() {
    }

    public static List<GameDefinition> load() {
        Index index = YamlResources.read("games/index.yaml", Index.class);
        return Stream.of(
                        index.games().stream().map(id -> game(id, GameCategory.OFFICIAL)),
                        index.romHacks().stream().map(id -> game(id, GameCategory.ROM_HACK)),
                        index.custom().stream().map(id -> game(id, GameCategory.CUSTOM)))
                .flatMap(games -> games)
                .toList();
    }

    private static GameDefinition game(String id, GameCategory category) {
        return YamlResources.read("games/" + id + ".yaml", GameDefinition.class).withId(id, category);
    }

    record Index(List<String> games, List<String> romHacks, List<String> custom) {
        Index {
            Objects.requireNonNull(games, "games");
            romHacks = romHacks == null ? List.of() : List.copyOf(romHacks);
            custom = custom == null ? List.of() : List.copyOf(custom);
        }
    }
}
