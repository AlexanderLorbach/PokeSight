package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.matchup.AttackStyle;
import de.alorbach.pokesight.ruleset.Move;
import de.alorbach.pokesight.ruleset.Species;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.List;
import java.util.Locale;

/**
 * Small icon for a move category, as a tag like the games' summary screens show: a yellow burst on red for physical,
 * light rings on blue for special. The team editor marks moves and Pokémon with them, so moves that suit a Pokémon
 * are seen at a glance. Drawn as vectors; colours in theme.css
 * ({@code .category-icon}).
 */
final class CategoryIcon {

    static final double WIDTH = 22;
    static final double HEIGHT = 14;
    private static final double CORNER = 4;
    private static final double BURST_RADIUS = 5.6;
    private static final double BURST_INNER_RADIUS = 2.6;
    private static final int RAYS = 10;
    /** The explanation shows almost at once on hover, or at the cursor on click. */
    private static final Duration TIP_DELAY = Duration.millis(150);
    private static final double TIP_OFFSET = 12;

    private CategoryIcon() {
    }

    /** The icon alone, e.g. inside {@link #forAttacker}. */
    static Node of(Move.Category category) {
        Rectangle tag = new Rectangle(WIDTH, HEIGHT);
        tag.setArcWidth(CORNER);
        tag.setArcHeight(CORNER);
        tag.getStyleClass().add("category-tag");
        Node symbol = switch (category) {
            case PHYSICAL -> burst();
            case SPECIAL -> rings();
        };
        StackPane icon = new StackPane(tag, symbol);
        icon.getStyleClass().addAll("category-icon", category.name().toLowerCase(Locale.ROOT));
        icon.setMinSize(WIDTH, HEIGHT);
        icon.setMaxSize(WIDTH, HEIGHT);
        return icon;
    }

    /** A move's category icon; hover or click says "Physical move" / "Special move". */
    static Node ofMove(Move.Category category) {
        Node icon = of(category);
        explain(icon, SearchField.categoryName(category) + " move");
        return icon;
    }

    /**
     * The kinds of move a Pokémon hits harder with: one icon, or both if Attack and Sp. Atk are about equal. Hover or
     * click shows why ("Special: SpA 65 > Atk 40").
     */
    static HBox forAttacker(Species.Form form) {
        HBox icons = new HBox(2);
        icons.getChildren().setAll(categories(AttackStyle.of(form)).stream().map(CategoryIcon::of).toList());
        icons.getStyleClass().add("attack-style");
        icons.setAlignment(Pos.CENTER);
        icons.setMinWidth(Region.USE_PREF_SIZE);
        explain(icons, styleText(form));
        return icons;
    }

    /** The kinds of move that suit this style: both for mixed attackers. */
    static List<Move.Category> categories(AttackStyle style) {
        return switch (style) {
            case PHYSICAL -> List.of(Move.Category.PHYSICAL);
            case SPECIAL -> List.of(Move.Category.SPECIAL);
            case MIXED -> List.of(Move.Category.PHYSICAL, Move.Category.SPECIAL);
        };
    }

    /** "Special: SpA 65 > Atk 40" and what it means. */
    static String styleText(Species.Form form) {
        int attack = form.stat(Species.Stat.ATTACK);
        int specialAttack = form.stat(Species.Stat.SPECIAL_ATTACK);
        return switch (AttackStyle.of(form)) {
            case PHYSICAL -> "Physical: Atk " + attack + " > SpA " + specialAttack + "\nPhysical moves hit harder";
            case SPECIAL -> "Special: SpA " + specialAttack + " > Atk " + attack + "\nSpecial moves hit harder";
            case MIXED -> "Mixed: Atk " + attack + " ≈ SpA " + specialAttack + "\nBoth kinds of moves work";
        };
    }

    /** A tooltip that shows quickly on hover and at the cursor on click. */
    private static void explain(Node node, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(TIP_DELAY);
        Tooltip.install(node, tip);
        node.setOnMouseClicked(event -> {
            if (!tip.isShowing()) {
                tip.show(node, event.getScreenX() + TIP_OFFSET, event.getScreenY() + TIP_OFFSET);
            }
            event.consume();
        });
    }

    /** A star with {@link #RAYS} points, alternating between the outer and inner radius. */
    private static SVGPath burst() {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < 2 * RAYS; i++) {
            double radius = i % 2 == 0 ? BURST_RADIUS : BURST_INNER_RADIUS;
            double angle = Math.PI * i / RAYS - Math.PI / 2;
            path.append(i == 0 ? 'M' : 'L').append(String.format(Locale.ROOT, "%.2f %.2f ",
                    BURST_RADIUS + radius * Math.cos(angle), BURST_RADIUS + radius * Math.sin(angle)));
        }
        SVGPath burst = new SVGPath();
        burst.setContent(path.append('Z').toString());
        burst.getStyleClass().add("category-symbol");
        return burst;
    }

    /** Three nested flat rings, centred in the tag. */
    private static StackPane rings() {
        StackPane rings = new StackPane();
        for (double[] radii : new double[][]{{7.5, 4.6}, {4.8, 2.9}, {2.1, 1.2}}) {
            Ellipse ring = new Ellipse(radii[0], radii[1]);
            ring.getStyleClass().add("category-ring");
            rings.getChildren().add(ring);
        }
        return rings;
    }
}
