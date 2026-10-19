package de.alorbach.pokesight.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixelArtTest {

    private static final int R = 0xffff0000;
    private static final int W = 0xffffffff;

    @Test
    void doublesFlatAreasLikeNearestNeighbour() {
        assertThat(PixelArt.scale2x(new int[] {R, W}, 2, 1)).containsExactly(
                R, R, W, W,
                R, R, W, W);
    }

    @Test
    void smoothsADiagonalStep() {
        // R W  ->  the bottom-left quarter of W joins the red diagonal
        // R R
        int[] scaled = PixelArt.scale2x(new int[] {R, W, R, R}, 2, 2);

        assertThat(scaled).containsExactly(
                R, R, W, W,
                R, R, R, W,
                R, R, R, R,
                R, R, R, R);
    }

    @Test
    void treatsAllTransparentPixelsAsOneColour() {
        int clearBlack = 0x00000000;
        int clearWhite = 0x00ffffff;

        assertThat(PixelArt.scale2x(new int[] {clearBlack, clearWhite}, 2, 1)).hasSize(8).containsOnly(0);
    }

    @Test
    void rejectsAWrongPixelCount() {
        assertThatThrownBy(() -> PixelArt.scale2x(new int[3], 2, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
