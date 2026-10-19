package de.alorbach.pokesight.capture;

import java.awt.Rectangle;
import java.util.Optional;

/** A rectangle on the desktop in screen coordinates, e.g. where the emulator shows the battle screen. */
public record CaptureRegion(int x, int y, int width, int height) {

    public static final int MIN_SIZE = 32;

    public CaptureRegion {
        if (width < MIN_SIZE || height < MIN_SIZE) {
            throw new IllegalArgumentException("Capture region must be at least %d x %d pixels".formatted(MIN_SIZE, MIN_SIZE));
        }
    }

    public Rectangle toRectangle() {
        return new Rectangle(x, y, width, height);
    }

    /** Reads {@code x,y,width,height}, the format older versions stored in the registry; empty if invalid. */
    public static Optional<CaptureRegion> parse(String text) {
        try {
            String[] parts = text.split(",");
            if (parts.length != 4) {
                return Optional.empty();
            }
            return Optional.of(new CaptureRegion(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
                    Integer.parseInt(parts[2].strip()), Integer.parseInt(parts[3].strip())));
        } catch (IllegalArgumentException e) { // also covers NumberFormatException
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "%d × %d at (%d, %d)".formatted(width, height, x, y);
    }
}
