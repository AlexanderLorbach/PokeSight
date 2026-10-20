package de.alorbach.pokesight.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The app's look for every window: dark theme ({@code ui/theme.css}), display font (Fredoka, SIL OFL), app icon
 * and a dark title bar drawn by the app itself ({@link TitleBar}).
 */
public final class Theme {

    /** Default size of the main window and the team editor; every view is laid out to fill it. */
    public static final double DEFAULT_WIDTH = 855;
    public static final double DEFAULT_HEIGHT = 640;

    /** Background of the title bar and the scene; its brightness also decides the colour of the native window buttons. */
    static final Color TITLE_BAR = Color.web("#0d0f13");
    private static final String FONT = "/ui/fonts/Fredoka-SemiBold.ttf";
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static final System.Logger LOG = System.getLogger(Theme.class.getName());
    private static boolean fontLoaded;
    private static List<Image> icons;

    private Theme() {
    }

    public static void apply(Scene scene) {
        loadFont();
        scene.setFill(TITLE_BAR);
        scene.getStylesheets().add(Objects.requireNonNull(Theme.class.getResource("/ui/theme.css")).toExternalForm());
    }

    /**
     * Gives a stage the app icon and lets its content extend into the title bar, so the app's own dark
     * {@link TitleBar} replaces the light Windows one while the native minimize/maximize/close buttons stay.
     * Must be called before the stage is shown. Falls back to the normal title bar where unsupported.
     */
    @SuppressWarnings("deprecation") // StageStyle.EXTENDED is a JavaFX 25 preview API (needs -Djavafx.enablePreview=true)
    public static void decorate(Stage stage) {
        stage.getIcons().setAll(icons());
        if (TitleBar.extendedWindowsEnabled()) {
            stage.initStyle(StageStyle.EXTENDED);
        }
    }

    /** A dark error dialog without the light Windows title bar. */
    /**
     * Runs a settings save; a failure (e.g. a read-only settings folder) is shown instead of breaking the action
     * the user started: the choice still applies to this session.
     */
    public static void saveOrWarn(Window owner, Runnable save) {
        try {
            save.run();
        } catch (UncheckedIOException e) {
            showError(owner, "Cannot save the settings", e.getMessage() + System.lineSeparator()
                    + "The change applies until PokeSight is closed.");
        }
    }

    public static void showError(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(header);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.initStyle(StageStyle.UNDECORATED);
        alert.getDialogPane().getStylesheets().add(Objects.requireNonNull(Theme.class.getResource("/ui/theme.css")).toExternalForm());
        alert.showAndWait();
    }

    static synchronized List<Image> icons() {
        if (icons == null) {
            List<Image> loaded = new ArrayList<>();
            for (int size : ICON_SIZES) {
                try (InputStream in = Theme.class.getResourceAsStream("/ui/icons/pokesight-" + size + ".png")) {
                    if (in != null) {
                        loaded.add(new Image(in));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read the app icon", e);
                }
            }
            icons = List.copyOf(loaded);
        }
        return icons;
    }

    private static synchronized void loadFont() {
        if (fontLoaded) {
            return;
        }
        try (InputStream in = Theme.class.getResourceAsStream(FONT)) {
            Font font = in == null ? null : Font.loadFont(in, 14);
            if (font == null) {
                LOG.log(System.Logger.Level.WARNING, "Display font {0} could not be loaded; using the default font", FONT);
            }
            fontLoaded = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + FONT, e);
        }
    }
}
