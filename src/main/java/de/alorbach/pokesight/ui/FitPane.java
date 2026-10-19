package de.alorbach.pokesight.ui;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

/**
 * Fills its whole area with one node, keeping the content readable at any size: the content is laid out at a
 * "design size" with exactly this area's aspect ratio, then scaled up or down to cover the area completely.
 * The design width is the smallest one at which the content still fits, so the content is shown as large as possible.
 */
final class FitPane extends Region {

    private static final double MIN_DESIGN_WIDTH = 280;
    private static final double MAX_DESIGN_WIDTH = 1200;
    private static final double STEP = 20;

    private final Region content;
    private final Scale scale = new Scale(1, 1, 0, 0);
    /** Content too long for the largest design size must not spill into the neighbouring pane. */
    private final Rectangle clip = new Rectangle();

    FitPane(Region content) {
        this.content = content;
        content.getTransforms().add(scale);
        getChildren().add(content);
        getStyleClass().add("fit-pane");
        setClip(clip);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        double ratio = height / width;
        // Never narrower than the content allows (e.g. the best-moves table), or its columns get cut off.
        double designWidth = Math.max(MIN_DESIGN_WIDTH, Math.ceil(content.minWidth(-1)));
        while (designWidth < MAX_DESIGN_WIDTH && content.prefHeight(designWidth) > designWidth * ratio) {
            designWidth += STEP;
        }
        content.resizeRelocate(0, 0, designWidth, designWidth * ratio);
        scale.setX(width / designWidth);
        scale.setY(width / designWidth);
        clip.setWidth(width);
        clip.setHeight(height);
    }

    @Override
    protected double computePrefWidth(double height) {
        return 0; // takes whatever space it is given
    }

    @Override
    protected double computePrefHeight(double width) {
        return 0;
    }
}
