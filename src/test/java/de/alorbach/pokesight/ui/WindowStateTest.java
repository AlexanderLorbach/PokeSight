package de.alorbach.pokesight.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WindowStateTest {

    private static final Rectangle2D LEFT_MONITOR = new Rectangle2D(-1920, 0, 1920, 1040);
    private static final Rectangle2D MAIN_MONITOR = new Rectangle2D(0, 0, 2560, 1400);

    @Test
    void restoresWindowsOnAnyCurrentMonitor() {
        Rectangle2D onLeftMonitor = new Rectangle2D(-1500, 100, 850, 680);
        Rectangle2D overTopRightCorner = new Rectangle2D(1710, -20, 850, 680); // partly above the screen is fine

        assertThat(WindowState.usable(onLeftMonitor, List.of(LEFT_MONITOR, MAIN_MONITOR))).contains(onLeftMonitor);
        assertThat(WindowState.usable(overTopRightCorner, List.of(MAIN_MONITOR))).contains(overTopRightCorner);
    }

    @Test
    void ignoresWindowsOnAMonitorThatIsGone() {
        Rectangle2D onLeftMonitor = new Rectangle2D(-1500, 100, 850, 680);

        assertThat(WindowState.usable(onLeftMonitor, List.of(MAIN_MONITOR))).isEmpty();
    }

    @Test
    void ignoresWindowsThatWouldOnlyPeekIn() {
        Rectangle2D almostOff = new Rectangle2D(2560 - 50, 100, 850, 680); // only 50 px visible

        assertThat(WindowState.usable(almostOff, List.of(MAIN_MONITOR))).isEmpty();
    }
}
