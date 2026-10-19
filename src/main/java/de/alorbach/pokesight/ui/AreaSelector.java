package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.capture.CaptureRegion;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Optional;

/** Dims all monitors and lets the user drag a rectangle around a part of the screen. Esc cancels. */
final class AreaSelector {

    private AreaSelector() {
    }

    /** Blocks until the user has selected an area or cancelled. Must be called on the JavaFX thread. */
    static Optional<CaptureRegion> select(Window owner, String instruction) {
        Rectangle2D desktop = Screen.getScreens().stream()
                .map(Screen::getBounds)
                .reduce(AreaSelector::union)
                .orElseThrow();

        Rectangle selection = new Rectangle();
        selection.getStyleClass().add("area-selection");
        selection.setVisible(false);
        Label hint = new Label(instruction + "  (Esc to cancel)");
        hint.getStyleClass().add("area-hint");
        Rectangle2D primary = Screen.getPrimary().getBounds();
        hint.relocate(primary.getMinX() - desktop.getMinX() + 24, primary.getMinY() - desktop.getMinY() + 24);

        Pane root = new Pane(selection, hint);
        root.getStyleClass().add("area-selector");
        root.setCursor(Cursor.CROSSHAIR);
        Scene scene = new Scene(root, desktop.getWidth(), desktop.getHeight(), Color.TRANSPARENT);
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setX(desktop.getMinX());
        stage.setY(desktop.getMinY());
        stage.setScene(scene);

        double[] start = new double[2];
        CaptureRegion[] result = new CaptureRegion[1];
        root.setOnMousePressed(event -> {
            start[0] = event.getX();
            start[1] = event.getY();
            selection.setVisible(true);
            selection.setX(start[0]);
            selection.setY(start[1]);
            selection.setWidth(0);
            selection.setHeight(0);
        });
        root.setOnMouseDragged(event -> {
            selection.setX(Math.min(start[0], event.getX()));
            selection.setY(Math.min(start[1], event.getY()));
            selection.setWidth(Math.abs(event.getX() - start[0]));
            selection.setHeight(Math.abs(event.getY() - start[1]));
        });
        root.setOnMouseReleased(event -> {
            if (selection.getWidth() >= CaptureRegion.MIN_SIZE && selection.getHeight() >= CaptureRegion.MIN_SIZE) {
                result[0] = new CaptureRegion(
                        (int) Math.round(desktop.getMinX() + selection.getX()),
                        (int) Math.round(desktop.getMinY() + selection.getY()),
                        (int) Math.round(selection.getWidth()),
                        (int) Math.round(selection.getHeight()));
                stage.close();
            } else {
                selection.setVisible(false); // a click or a tiny drag: let the user try again
            }
        });
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private static Rectangle2D union(Rectangle2D a, Rectangle2D b) {
        double minX = Math.min(a.getMinX(), b.getMinX());
        double minY = Math.min(a.getMinY(), b.getMinY());
        return new Rectangle2D(minX, minY, Math.max(a.getMaxX(), b.getMaxX()) - minX, Math.max(a.getMaxY(), b.getMaxY()) - minY);
    }
}
