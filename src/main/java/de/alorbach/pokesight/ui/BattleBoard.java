package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.BattleMonitor;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.battle.Sighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What the battle view shows: the scanned opponents with the user's corrections applied,
 * followed by the Pokémon the user added by hand.
 *
 * <p>A correction belongs to a slot and to what was read there, so it stays while the screen keeps
 * showing the misread name and stops applying as soon as a different Pokémon is read in that slot.
 */
final class BattleBoard {

    /**
     * One card.
     *
     * @param index     slot of the scanned opponent, or position in the added list
     * @param added     picked by the user instead of scanned
     * @param readText  OCR text of the slot (empty for added Pokémon)
     * @param corrected the user replaced what the scan recognized
     */
    record Entry(int index, boolean added, String readText, Optional<PokemonId> pokemon, OptionalInt level, boolean corrected) {
    }

    private record Correction(Optional<PokemonId> detected, PokemonId chosen) {
    }

    private List<Sighting> sightings = List.of();
    private final Map<Integer, Correction> corrections = new HashMap<>();
    private final List<PokemonId> added = new ArrayList<>();
    private BattleMonitor.State lastState = BattleMonitor.State.WAITING;

    void update(BattleMonitor.State state, List<Sighting> newSightings) {
        if (state == BattleMonitor.State.WAITING && lastState == BattleMonitor.State.IN_BATTLE) {
            corrections.clear(); // the battle is over
            added.clear();
        }
        lastState = state;
        sightings = List.copyOf(newSightings);
    }

    List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < sightings.size(); i++) {
            Sighting sighting = sightings.get(i);
            Correction correction = corrections.get(i);
            boolean corrected = correction != null && correction.detected().equals(sighting.pokemon());
            entries.add(new Entry(i, false, sighting.text(),
                    corrected ? Optional.of(correction.chosen()) : sighting.pokemon(), sighting.level(), corrected));
        }
        for (int i = 0; i < added.size(); i++) {
            entries.add(new Entry(i, true, "", Optional.of(added.get(i)), OptionalInt.empty(), false));
        }
        return entries;
    }

    void change(Entry entry, PokemonId chosen) {
        if (entry.added()) {
            added.set(entry.index(), chosen);
        } else {
            corrections.put(entry.index(), new Correction(sightings.get(entry.index()).pokemon(), chosen));
        }
    }

    void add(PokemonId pokemon) {
        added.add(pokemon);
    }

    void remove(Entry entry) {
        if (!entry.added()) {
            throw new IllegalArgumentException("Only added Pokémon can be removed");
        }
        added.remove(entry.index());
    }

    void clear() {
        sightings = List.of();
        corrections.clear();
        added.clear();
    }
}
