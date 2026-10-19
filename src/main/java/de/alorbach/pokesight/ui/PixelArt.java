package de.alorbach.pokesight.ui;

/**
 * Scale2x (also known as EPX, https://www.scale2x.it/algorithm): doubles pixel art, turning stair-step diagonals into
 * smooth slopes without blurring. Works on ARGB pixel arrays, row by row.
 */
final class PixelArt {

    private PixelArt() {
    }

    /** @return the image at twice the width and height */
    static int[] scale2x(int[] argb, int width, int height) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " pixels, got " + argb.length);
        }
        int[] result = new int[argb.length * 4];
        int outWidth = width * 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = pixel(argb, width, height, x, y);
                int a = pixel(argb, width, height, x, y - 1);
                int b = pixel(argb, width, height, x + 1, y);
                int c = pixel(argb, width, height, x - 1, y);
                int d = pixel(argb, width, height, x, y + 1);
                int top = y * 2 * outWidth + x * 2;
                int bottom = top + outWidth;
                result[top] = c == a && c != d && a != b ? a : p;
                result[top + 1] = a == b && a != c && b != d ? b : p;
                result[bottom] = d == c && d != b && c != a ? c : p;
                result[bottom + 1] = b == d && b != a && d != c ? d : p;
            }
        }
        return result;
    }

    /** The pixel, clamped to the image's edge; all fully transparent pixels count as the same colour. */
    private static int pixel(int[] argb, int width, int height, int x, int y) {
        int value = argb[Math.clamp(y, 0, height - 1) * width + Math.clamp(x, 0, width - 1)];
        return value >>> 24 == 0 ? 0 : value;
    }
}
