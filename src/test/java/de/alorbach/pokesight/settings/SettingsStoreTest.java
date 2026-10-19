package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.ruleset.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsStoreTest {

    private static final Team TEAM = new Team(List.of(
            new Team.Member(PokemonId.of(390), List.of(52, 10)),
            new Team.Member(new PokemonId(413, 1), List.of())));

    @TempDir
    Path directory;

    @Test
    void savesAndLoadsAppSettings() {
        SettingsStore store = new SettingsStore(directory);
        AppSettings settings = AppSettings.DEFAULT.withGame("platinum", Language.DE)
                .withWindow(new AppSettings.WindowBounds(1708, -8, 850, 680, false));

        store.saveApp(settings);

        assertThat(new SettingsStore(directory).app()).isEqualTo(settings);
    }

    @Test
    void savesAndLoadsGameSettingsAsReadableYaml() throws Exception {
        SettingsStore store = new SettingsStore(directory);
        GameSettings settings = GameSettings.DEFAULT
                .withSingleArea(new CaptureRegion(2, 210, 757, 211))
                .withDoubleArea(null)
                .withTeam(TEAM);

        store.saveGame("platinum", settings);

        assertThat(new SettingsStore(directory).game("platinum")).isEqualTo(settings);
        assertThat(new SettingsStore(directory).game("platinum").toTeam()).isEqualTo(TEAM);
        String yaml = Files.readString(directory.resolve("games/platinum.yaml"));
        assertThat(yaml).startsWith("# PokeSight settings").contains("singleArea:", "width: 757", "pokemon: 390");
    }

    @Test
    void missingFilesGiveDefaults() {
        SettingsStore store = new SettingsStore(directory);

        assertThat(store.app()).isEqualTo(AppSettings.DEFAULT);
        assertThat(store.game("platinum")).isEqualTo(GameSettings.DEFAULT);
    }

    @Test
    void unreadableFilesAreIgnored() throws Exception {
        Files.writeString(directory.resolve("settings.yaml"), "game: [this is: not valid");
        Files.createDirectories(directory.resolve("games"));
        Files.writeString(directory.resolve("games/platinum.yaml"), "singleArea: { x: 0, y: 0, width: 5, height: 5 }");

        SettingsStore store = new SettingsStore(directory);

        assertThat(store.app()).isEqualTo(AppSettings.DEFAULT);
        assertThat(store.game("platinum")).isEqualTo(GameSettings.DEFAULT); // area below the minimum size
    }

    @Test
    void rejectsGameIdsThatCouldLeaveTheFolder() {
        SettingsStore store = new SettingsStore(directory);

        assertThatThrownBy(() -> store.game("../evil")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheOldRegistryTeamFormat() {
        assertThat(LegacyPreferences.parseTeam("390.0:52,10;413.1:")).isEqualTo(TEAM);
        assertThat(LegacyPreferences.parseTeam("")).isEqualTo(Team.EMPTY);
        assertThatThrownBy(() -> LegacyPreferences.parseTeam("hello")).isInstanceOf(IllegalArgumentException.class);
    }
}
