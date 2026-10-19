package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.battle.BattleMonitor.State;
import de.alorbach.pokesight.battle.BattleMonitor.Update;
import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BattleMonitorTest {

    private static final CaptureRegion SINGLE = new CaptureRegion(0, 0, 256, 64);
    private static final CaptureRegion DOUBLE = new CaptureRegion(0, 0, 256, 128);
    private static final NameMatcher MATCHER = new NameMatcher(RulesetLoader.load("gen4"), Language.EN);

    private final FakeRecognizer recognizer = new FakeRecognizer();
    private final List<CaptureRegion> captured = new ArrayList<>();
    private final List<Update> updates = new ArrayList<>();
    private RuntimeException screenFailure;

    private BattleMonitor monitor(CaptureRegion... regions) {
        return new BattleMonitor(region -> {
            if (screenFailure != null) {
                throw screenFailure;
            }
            captured.add(region);
            return new BufferedImage(region.width(), region.height(), BufferedImage.TYPE_INT_RGB);
        }, List.of(regions), new BattleScanner(recognizer, MATCHER, false), updates::add);
    }

    @Test
    void startsBattleOnlyAfterTwoMatchingScans() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines("GENGAR Lv32");

        monitor.poll();
        assertThat(updates).isEmpty();
        monitor.poll();

        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update.state()).isEqualTo(State.IN_BATTLE);
            assertThat(update.foes()).extracting(Sighting::pokemon).containsExactly(Optional.of(PokemonId.of(94)));
        });
    }

    @Test
    void ignoresOneOffMisreads() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines("GENGAR Lv32");
        monitor.poll();
        recognizer.showLines("BIDOOF Lv3");
        monitor.poll();

        assertThat(updates).isEmpty();
    }

    @Test
    void ignoresOcrNoiseInTheRawText() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines("GENGAR Lv32");
        monitor.poll();
        monitor.poll();
        recognizer.showLines("GENGAP Lv32"); // same Pokémon, different raw text
        monitor.poll();
        monitor.poll();

        assertThat(updates).hasSize(1);
    }

    @Test
    void endsBattleAfterSeveralScansWithoutName() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines("GENGAR Lv32");
        monitor.poll();
        monitor.poll();
        recognizer.showLines();
        for (int i = 1; i < BattleMonitor.END_AFTER_MISSES; i++) {
            monitor.poll();
        }
        assertThat(updates).extracting(Update::state).containsExactly(State.IN_BATTLE); // animations hide the boxes briefly

        monitor.poll();

        assertThat(updates).extracting(Update::state).containsExactly(State.IN_BATTLE, State.WAITING);
    }

    @Test
    void usesTheRegionWithMostRecognizedNames() {
        BattleMonitor monitor = monitor(SINGLE, DOUBLE);
        for (int i = 0; i < 2; i++) {
            // The scanner reads every area four times (dark/light text, with and without joined strokes).
            for (int read = 0; read < 4; read++) {
                recognizer.showOnceLines("ZUBAT Lv12");                // single-battle area: catches one of them
            }
            for (int read = 0; read < 4; read++) {
                recognizer.showOnceLines("ZUBAT Lv12", "GOLBAT Lv22"); // double-battle area: both
            }
            monitor.poll();
        }

        assertThat(captured).containsExactly(SINGLE, DOUBLE, SINGLE, DOUBLE);
        assertThat(updates).singleElement().satisfies(update ->
                assertThat(update.foes()).extracting(Sighting::pokemon)
                        .containsExactly(Optional.of(PokemonId.of(41)), Optional.of(PokemonId.of(42))));
    }

    @Test
    void manualScanShowsResultImmediatelyAndKeepsIt() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines("QWXZRT Lv20"); // unreadable name: auto-detection would never start a battle

        monitor.manualScan();
        recognizer.showLines();
        for (int i = 0; i < BattleMonitor.END_AFTER_MISSES * 2; i++) {
            monitor.poll();
        }

        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update.state()).isEqualTo(State.IN_BATTLE);
            assertThat(update.foes()).extracting(Sighting::text).containsExactly("QWXZRT Lv20");
        });
    }

    @Test
    void manualScanWithoutResultKeepsCurrentFoes() {
        BattleMonitor monitor = monitor(SINGLE);
        recognizer.showLines();

        monitor.manualScan();

        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update.state()).isEqualTo(State.WAITING);
            assertThat(update.message()).contains("no Pokémon");
        });
    }

    @Test
    void reportsScreenErrorsAndRecovers() {
        BattleMonitor monitor = monitor(SINGLE);
        screenFailure = new IllegalStateException("display gone");
        monitor.poll();
        screenFailure = null;
        monitor.poll();

        assertThat(updates).extracting(Update::state).containsExactly(State.ERROR, State.WAITING);
        assertThat(updates.getFirst().message()).contains("display gone");
    }

    @Test
    void keepsTheCapturesForThePreview() {
        BattleMonitor monitor = monitor(SINGLE, DOUBLE);

        monitor.poll();

        assertThat(monitor.lastCaptures()).extracting(BufferedImage::getHeight).containsExactly(64, 128);
    }
}
