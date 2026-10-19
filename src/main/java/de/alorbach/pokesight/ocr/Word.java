package de.alorbach.pokesight.ocr;

/** One recognized word and its bounding box in the image that was read. */
public record Word(String text, int left, int top, int right, int bottom) {

    public int centerY() {
        return (top + bottom) / 2;
    }
}
