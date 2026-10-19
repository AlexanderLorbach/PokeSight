package de.alorbach.pokesight.capture;

import java.awt.image.BufferedImage;

/** Takes a screenshot of a part of the desktop. */
@FunctionalInterface
public interface ScreenSource {

    BufferedImage capture(CaptureRegion region);
}
