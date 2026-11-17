package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.capture.CaptureRegion;
import de.alorbach.pokesight.capture.ScreenSource;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scans the selected screen areas in the background and notifies the listener whenever the
 * battle situation changes (Observer). All scanning runs on one worker thread, which is also
 * the thread the listener is called on.
 *
 * <p>Auto-detection: a battle starts (or its opponents change) when the same Pokémon are read in at least
 * {@value #STABLE_READINGS} of the last {@value #RECENT_READINGS} scans and ends after {@value #END_AFTER_MISSES}
 * scans without any recognized name. A manual scan shows whatever it finds and stays until auto-detection sees a battle or the user resets.
 *
 * <p>If areas of the player's own name boxes are selected (single and/or double battle), each Pokémon read there in
 * two of the last three scans is one of the player's active ones: one in a single battle, two in a double battle, so a
 * scan that misses one of two boxes does not lose the other. The player's team is matched there more loosely than other
 * species (see {@link #setTeam}). Like the opponents' areas, the own area with the most recognized names wins. They are
 * forgotten when the battle ends.
 */
public final class BattleMonitor implements AutoCloseable {

    public enum State { WAITING, IN_BATTLE, ERROR }

    /** @param active the player's Pokémon read in their own name boxes, left to right; empty if unknown */
    public record Update(State state, String message, List<Sighting> foes, List<PokemonId> active) {
        public Update {
            foes = List.copyOf(foes);
            active = active == null ? List.of() : List.copyOf(active);
        }

        public Update(State state, String message, List<Sighting> foes) {
            this(state, message, foes, List.of());
        }

        /** Same state, message and Pokémon; differences in the raw OCR text do not count. */
        boolean sameAs(Update other) {
            return state == other.state && message.equals(other.message) && sameSightings(foes, other.foes)
                    && active.equals(other.active);
        }
    }

    static final int END_AFTER_MISSES = 6;
    static final int RECENT_READINGS = 3;
    static final int STABLE_READINGS = 2;
    /** A double battle has two of the player's Pokémon on the field. */
    static final int MAX_ACTIVE = 2;
    private static final System.Logger LOG = System.getLogger(BattleMonitor.class.getName());
    private static final Update WAITING_UPDATE = new Update(State.WAITING, "Waiting for a battle", List.of());

    private final ScreenSource screen;
    private final List<CaptureRegion> regions;
    private final List<CaptureRegion> ownRegions;
    private final BattleScanner scanner;
    private final Consumer<Update> listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "battle-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<BufferedImage> lastCaptures = List.of();
    private volatile Set<Integer> team = Set.of();
    private Update current = WAITING_UPDATE;
    private final Deque<List<Sighting>> recentReadings = new ArrayDeque<>();
    private final Deque<List<PokemonId>> recentOwn = new ArrayDeque<>();
    private List<PokemonId> active = List.of();
    private boolean manual;
    private int misses;

    /** @param regions the areas to read; the one with the most recognized names wins */
    public BattleMonitor(ScreenSource screen, List<CaptureRegion> regions, BattleScanner scanner, Consumer<Update> listener) {
        this(screen, regions, List.of(), scanner, listener);
    }

    /** @param ownRegions the areas of the player's own name boxes (single and/or double battle); empty if none */
    public BattleMonitor(ScreenSource screen, List<CaptureRegion> regions, List<CaptureRegion> ownRegions, BattleScanner scanner,
                         Consumer<Update> listener) {
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("At least one capture region is required");
        }
        this.screen = Objects.requireNonNull(screen);
        this.regions = List.copyOf(regions);
        this.ownRegions = List.copyOf(ownRegions);
        this.scanner = Objects.requireNonNull(scanner);
        this.listener = Objects.requireNonNull(listener);
    }

    public void start(Duration interval) {
        worker.execute(() -> listener.accept(current));
        worker.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Reads the screen now and shows the result even if auto-detection would not count it as a battle. */
    public void scanNow() {
        worker.execute(this::manualScan);
    }

    /** Forgets the current battle and goes back to waiting for the next one. */
    public void reset() {
        worker.execute(() -> {
            manual = false;
            misses = 0;
            recentReadings.clear();
            forgetActive();
            publish(WAITING_UPDATE);
        });
    }

    /**
     * The species of the player's team: in the own name boxes they are found despite more misread letters, since only a
     * few Pokémon can stand there. May be called from any thread; the next scan uses it.
     */
    public void setTeam(Collection<Integer> species) {
        team = Set.copyOf(species);
    }

    /** The screenshots of the last scan, one per region, for the capture preview. */
    public List<BufferedImage> lastCaptures() {
        return lastCaptures;
    }

    /** One auto-detection step. Package-private so tests can drive it without threads. */
    void poll() {
        try {
            List<Sighting> sightings = read();
            boolean recognized = !sightings.isEmpty();
            recentReadings.addLast(sightings);
            if (recentReadings.size() > RECENT_READINGS) {
                recentReadings.removeFirst();
            }
            long agreeing = recentReadings.stream().filter(reading -> BattleScanner.samePokemon(reading, sightings)).count();
            if (recognized) {
                misses = 0;
                if (agreeing >= STABLE_READINGS) {
                    manual = false;
                    publish(new Update(State.IN_BATTLE, "In battle", sightings, active));
                } else if (current.state() == State.IN_BATTLE) {
                    publish(new Update(current.state(), current.message(), current.foes(), active));
                }
            } else if (current.state() == State.IN_BATTLE && !manual && ++misses >= END_AFTER_MISSES) {
                forgetActive();
                publish(WAITING_UPDATE);
            } else if (current.state() == State.ERROR) {
                publish(WAITING_UPDATE);
            } else if (current.state() == State.IN_BATTLE) {
                publish(new Update(current.state(), current.message(), current.foes(), active));
            }
        } catch (RuntimeException | Error e) {
            // Anything thrown out of a scheduled task silently ends the schedule: report it and keep polling.
            failed("Reading the screen failed", e);
        }
    }

    /** Shows the failure; errors the JVM cannot recover from (out of memory) are passed on after that. */
    private void failed(String what, Throwable e) {
        LOG.log(System.Logger.Level.ERROR, what, e);
        publish(new Update(State.ERROR, what + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                current.foes(), current.active()));
        if (e instanceof VirtualMachineError fatal) {
            throw fatal;
        }
    }

    /** One manual scan. Package-private so tests can drive it without threads. */
    void manualScan() {
        try {
            List<Sighting> sightings = read();
            if (sightings.isEmpty()) {
                publish(new Update(current.state(), "Scan found no Pokémon name - use Add to pick one", current.foes(), active));
            } else {
                manual = true;
                misses = 0;
                publish(new Update(State.IN_BATTLE, "Scanned manually", sightings, active));
            }
        } catch (RuntimeException | Error e) {
            failed("Reading the screen failed", e);
        }
    }

    /**
     * Scans every region and keeps the reading with the most recognized names (first region on a tie); the player's
     * own areas the same way.
     */
    private List<Sighting> read() {
        List<BufferedImage> captures = new ArrayList<>();
        List<List<Sighting>> readings = new ArrayList<>();
        for (CaptureRegion region : regions) {
            BufferedImage image = screen.capture(region);
            captures.add(image);
            readings.add(scanner.scan(image));
        }
        List<List<Sighting>> ownReadings = new ArrayList<>();
        Set<Integer> ownTeam = team;
        for (CaptureRegion region : ownRegions) {
            BufferedImage image = screen.capture(region);
            captures.add(image);
            ownReadings.add(scanner.scan(image, ownTeam));
        }
        if (!ownRegions.isEmpty()) {
            readOwn(mostNames(ownReadings).stream().map(Sighting::pokemon).distinct().limit(MAX_ACTIVE).toList());
        }
        lastCaptures = List.copyOf(captures);
        return mostNames(readings);
    }

    /** The reading with the most recognized names; the first one on a tie. */
    private static List<Sighting> mostNames(List<List<Sighting>> readings) {
        return readings.stream().max(Comparator.comparingInt(List::size)).orElseThrow();
    }

    /**
     * Each Pokémon in two of the last three own readings is an active one; single misreads are not. They are ordered by
     * their mean position in the readings (upper box first): a scan that missed the upper box has the lower one first.
     * Without any, the active ones stay (a menu may cover the boxes).
     */
    private void readOwn(List<PokemonId> reading) {
        recentOwn.addLast(reading);
        if (recentOwn.size() > RECENT_READINGS) {
            recentOwn.removeFirst();
        }
        Map<PokemonId, Integer> count = new LinkedHashMap<>();
        Map<PokemonId, Integer> positions = new HashMap<>();
        for (List<PokemonId> recent : recentOwn.reversed()) { // newest first: ties keep the newest order
            for (int i = 0; i < recent.size(); i++) {
                count.merge(recent.get(i), 1, Integer::sum);
                positions.merge(recent.get(i), i, Integer::sum);
            }
        }
        List<PokemonId> stable = count.entrySet().stream()
                .filter(entry -> entry.getValue() >= STABLE_READINGS)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingDouble(pokemon -> (double) positions.get(pokemon) / count.get(pokemon)))
                .limit(MAX_ACTIVE)
                .toList();
        if (!stable.isEmpty()) {
            active = stable;
        }
    }

    private void forgetActive() {
        active = List.of();
        recentOwn.clear();
    }

    private static boolean sameSightings(List<Sighting> a, List<Sighting> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameAs(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void publish(Update update) {
        if (!update.sameAs(current)) {
            current = update;
            listener.accept(update);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try {
            // The OCR engine is closed right after this; it must not be in use any more.
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Battle monitor did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
