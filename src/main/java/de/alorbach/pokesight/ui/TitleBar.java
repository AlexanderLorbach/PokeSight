package de.alorbach.pokesight.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The app's own dark title bar (replaces the light Windows one, see {@link Theme#decorate}): Poké Ball, title and
 * optional subtitle on the left, the view's controls on the right, then the native window buttons. The empty
 * background and the title can be dragged to move the window; buttons stay clickable.
 *
 * <p>Uses the JavaFX 25 preview API {@link HeaderBar}, which throws unless {@code -Djavafx.enablePreview=true} is
 * set (the Gradle run and the installer set it). Without it, the same header is a plain row below the Windows
 * title bar.
 */
@SuppressWarnings("deprecation") // HeaderBar is a JavaFX 25 preview API, deprecated until it becomes final
final class TitleBar {

    private TitleBar() {
    }

    static boolean extendedWindowsEnabled() {
        return Boolean.getBoolean("javafx.enablePreview");
    }

    /** Hides the native minimize/maximize/close buttons (borderless mode) or brings them back. */
    static void showWindowButtons(Stage stage, boolean show) {
        if (extendedWindowsEnabled()) {
            HeaderBar.setPrefButtonHeight(stage, show ? HeaderBar.USE_DEFAULT_SIZE : 0);
        }
    }

    /**
     * @param subtitle may be null
     * @param controls nodes on the right, before the window buttons; may be empty
     */
    static Node of(String title, String subtitle, Node... controls) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("app-title");
        VBox titles = new VBox(titleLabel);
        if (subtitle != null) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("app-subtitle");
            titles.getChildren().add(subtitleLabel);
        }
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox leading = new HBox(10, PokeBall.of(26), titles);
        leading.setAlignment(Pos.CENTER_LEFT);

        HBox trailing = new HBox(8, controls);
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.getStyleClass().add("title-bar-controls");

        if (!extendedWindowsEnabled()) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox plain = new HBox(leading, spacer, trailing);
            plain.setAlignment(Pos.CENTER_LEFT);
            plain.getStyleClass().add("title-bar");
            return plain;
        }
        HeaderBar.setDragType(leading, HeaderDragType.DRAGGABLE_SUBTREE);
        HeaderBar bar = new HeaderBar();
        bar.getStyleClass().add("title-bar");
        bar.setLeading(leading);
        bar.setTrailing(trailing);
        return bar;
    }
}
