package de.alorbach.pokesight.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureRegionTest {

    @Test
    void readsTheOldRegistryFormat() {
        // monitors left of the primary have negative x
        assertThat(CaptureRegion.parse("-1920,40,512,384")).contains(new CaptureRegion(-1920, 40, 512, 384));
    }

    @Test
    void ignoresInvalidStoredValues() {
        assertThat(CaptureRegion.parse("")).isEmpty();
        assertThat(CaptureRegion.parse("1,2,3")).isEmpty();
        assertThat(CaptureRegion.parse("a,b,c,d")).isEmpty();
        assertThat(CaptureRegion.parse("0,0,5,5")).isEmpty(); // too small
    }

    @Test
    void rejectsTinyRegions() {
        assertThatThrownBy(() -> new CaptureRegion(0, 0, 10, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
