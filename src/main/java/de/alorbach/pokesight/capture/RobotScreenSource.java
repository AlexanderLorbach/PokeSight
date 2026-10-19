package de.alorbach.pokesight.capture;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.Comparator;

/** Takes screenshots with {@link Robot}. Works with any emulator. */
public final class RobotScreenSource implements ScreenSource {

    private final Robot robot;

    public RobotScreenSource() throws AWTException {
        this.robot = new Robot();
    }

    @Override
    public BufferedImage capture(CaptureRegion region) {
        // With display scaling the plain capture is downscaled; take the variant with the most pixels.
        MultiResolutionImage capture = robot.createMultiResolutionScreenCapture(region.toRectangle());
        Image largest = capture.getResolutionVariants().stream()
                .max(Comparator.comparingInt(image -> image.getWidth(null) * image.getHeight(null)))
                .orElseThrow();
        if (largest instanceof BufferedImage buffered) {
            return buffered;
        }
        BufferedImage copy = new BufferedImage(largest.getWidth(null), largest.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(largest, 0, 0, null);
        graphics.dispose();
        return copy;
    }
}
