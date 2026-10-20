package de.alorbach.pokesight.ocr;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class HpBarsTest {

    private static final Color GREEN = new Color(0x2F, 0xCD, 0x30);
    private static final Color RED = new Color(0xEE, 0x40, 0x40);
    private static final Color EMPTY = new Color(0x25, 0x38, 0x58);
    private static final Color SKY = new Color(0x4A, 0xB0, 0xEE);

    private final BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
    private final Graphics2D graphics = image.createGraphics();

    private void fill(Color color, int x, int y, int width, int height) {
        graphics.setColor(color);
        graphics.fillRect(x, y, width, height);
    }

    @Test
    void findsAFullBarInAGreyTrack() {
        fill(Color.DARK_GRAY, 0, 0, 640, 360);
        fill(Color.LIGHT_GRAY, 196, 96, 128, 14);
        fill(GREEN, 200, 100, 120, 6);

        assertThat(HpBars.bars(image)).containsExactly(new Rectangle(200, 100, 120, 6));
    }

    @Test
    void anAlmostEmptyBarCountsWithItsEmptyPart() {
        fill(new Color(0xC8, 0x97, 0x77), 0, 0, 640, 360); // sand
        fill(EMPTY, 200, 100, 120, 8);
        fill(RED, 200, 100, 14, 8); // too short to count as a bar on its own

        assertThat(HpBars.bars(image)).singleElement().satisfies(bar -> {
            assertThat(bar.x).isEqualTo(200);
            assertThat(bar.width).isBetween(115, 120);
        });
        assertThat(HpBars.labelAreas(image)).singleElement().satisfies(area -> assertThat(area.getMaxY()).isEqualTo(100));
    }

    @Test
    void findsABarInATranslucentFrameOverTheSky() {
        fill(SKY, 0, 0, 640, 360);
        fill(new Color(0x1F, 0x79, 0x84), 196, 96, 128, 16); // the frame takes on the sky's colour
        fill(GREEN, 200, 100, 120, 8);

        assertThat(HpBars.bars(image)).containsExactly(new Rectangle(200, 100, 120, 8));
    }

    @Test
    void ignoresStripsOfGrass() {
        fill(new Color(0x30, 0x90, 0x20), 0, 0, 640, 360);
        fill(GREEN, 200, 100, 120, 6); // brighter green, but grass above and below

        assertThat(HpBars.bars(image)).isEmpty();
    }

    @Test
    void ignoresSmallSpotsOnAnUnevenDarkBackground() {
        for (int x = 0; x < 640; x += 4) {
            fill(x % 8 == 0 ? new Color(0x20, 0x20, 0x30) : new Color(0x60, 0x50, 0x70), x, 0, 4, 360);
        }
        fill(Color.YELLOW, 200, 100, 8, 4); // a sparkle

        assertThat(HpBars.bars(image)).isEmpty();
    }
}
