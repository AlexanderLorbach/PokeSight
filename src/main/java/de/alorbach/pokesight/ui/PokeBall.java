package de.alorbach.pokesight.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/** A Poké Ball emblem drawn from shapes, so no image file is needed. */
final class PokeBall {

    private static final Color RED = Color.web("#e3350d");
    private static final Color OUTLINE = Color.web("#1d1d1f");

    private PokeBall() {
    }

    static Node of(double size) {
        double radius = size / 2;
        double line = Math.max(1, size * 0.07);
        Circle ball = new Circle(radius, radius, radius - line / 2);
        ball.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, RED), new Stop(0.5, RED), new Stop(0.5, Color.WHITE), new Stop(1, Color.WHITE)));
        ball.setStroke(OUTLINE);
        ball.setStrokeWidth(line);
        Rectangle band = new Rectangle(0, radius - line / 2, size, line);
        band.setFill(OUTLINE);
        Circle button = new Circle(radius, radius, size * 0.16);
        button.setFill(Color.WHITE);
        button.setStroke(OUTLINE);
        button.setStrokeWidth(line);
        return new Group(ball, band, button);
    }
}
