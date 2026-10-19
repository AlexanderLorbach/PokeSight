package de.alorbach.pokesight.settings;

import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.matchup.Team;
import de.alorbach.pokesight.ruleset.Language;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Versions before 0.3 kept the settings in the Java preferences (on Windows: the registry key
 * {@code HKCU\Software\JavaSoft\Prefs\de\alorbach\pokesight}). Copies them into the settings files once and
 * deletes the registry key.
 */
final class LegacyPreferences {

    private static final String NODE = "de/alorbach/pokesight";
    private static final System.Logger LOG = System.getLogger(LegacyPreferences.class.getName());

    private LegacyPreferences() {
    }

    static void migrateTo(SettingsStore store) {
        try {
            Preferences root = Preferences.userRoot();
            if (!root.nodeExists(NODE)) {
                return;
            }
            if (!store.hasAppFile()) {
                copy(root.node(NODE + "/ui"), store);
            }
            root.node(NODE).removeNode();
            root.flush();
            LOG.log(System.Logger.Level.INFO, "Moved the settings from the registry to " + store.directory());
        } catch (BackingStoreException | RuntimeException e) {
            // Losing old settings must never stop the app; the user can select the areas again.
            LOG.log(System.Logger.Level.WARNING, "Could not move the old settings out of the registry", e);
        }
    }

    private static void copy(Preferences ui, SettingsStore store) throws BackingStoreException {
        AppSettings app = AppSettings.DEFAULT;
        String game = ui.get("game", null);
        Language language = parseLanguage(ui.get("language", ""));
        if (game != null) {
            app = app.withGame(game, language);
        }
        double width = ui.getDouble("window.width", -1);
        double height = ui.getDouble("window.height", -1);
        if (width > 0 && height > 0) {
            app = app.withWindow(new AppSettings.WindowBounds(ui.getDouble("window.x", 0), ui.getDouble("window.y", 0),
                    width, height, ui.getBoolean("window.maximized", false)));
        }
        store.saveApp(app);

        Map<String, GameSettings> games = new HashMap<>();
        for (String key : ui.keys()) {
            String[] parts = key.split("\\.");
            String value = ui.get(key, "");
            if (parts.length == 3 && parts[0].equals("area")) {
                CaptureRegion area = CaptureRegion.parse(value).orElse(null);
                GameSettings settings = games.getOrDefault(parts[1], GameSettings.DEFAULT);
                games.put(parts[1], parts[2].equals("single") ? settings.withSingleArea(area) : settings.withDoubleArea(area));
            } else if (parts.length == 2 && parts[0].equals("team")) {
                games.put(parts[1], games.getOrDefault(parts[1], GameSettings.DEFAULT).withTeam(parseTeam(value)));
            }
        }
        games.forEach(store::saveGame);
    }

    /** Java preferences escape capitals with a slash ("DE" is stored as "/D/E"); {@code Preferences#get} undoes that. */
    private static Language parseLanguage(String text) {
        try {
            return text.isEmpty() ? null : Language.valueOf(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Old format: members separated by ";", each "species.form:move,move". */
    static Team parseTeam(String text) {
        if (text.isBlank()) {
            return Team.EMPTY;
        }
        List<Team.Member> members = new ArrayList<>();
        for (String member : text.split(";")) {
            String[] parts = member.split(":", -1);
            String[] pokemon = parts[0].split("\\.");
            if (parts.length != 2 || pokemon.length != 2) {
                throw new IllegalArgumentException("Not a team member: " + member);
            }
            List<Integer> moves = parts[1].isBlank() ? List.of()
                    : Arrays.stream(parts[1].split(",")).map(String::strip).map(Integer::parseInt).toList();
            members.add(new Team.Member(new PokemonId(Integer.parseInt(pokemon[0]), Integer.parseInt(pokemon[1])), moves));
        }
        return new Team(members);
    }
}
