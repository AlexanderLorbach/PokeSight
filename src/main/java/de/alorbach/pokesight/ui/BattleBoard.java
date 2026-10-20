package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.BattleMonitor;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.battle.Sighting;
import de.alorbach.pokesight.ruleset.PokemonType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the battle view shows: the scanned opponents with the user's corrections applied,
 * followed by the Pokémon the user added by hand.
 *
 * <p>A correction belongs to a slot and to what was read there, so it stays while the screen keeps
 * showing the misread name and stops applying as soon as a different Pokémon is read in that slot. A Tera type the
 * user picked belongs to a slot and Pokémon the same way.
 */
final class BattleBoard {

    /**
     * One card.
     *
     * @param index     slot of the scanned opponent, or position in the added list
     * @param added     picked by the user instead of scanned
     * @param readText  OCR text of the slot (empty for added Pokémon)
     * @param corrected the user replaced what the scan recognized
     * @param tera      the type it terastallized into; null if it did not
     */
    record Entry(int index, boolean added, String readText, PokemonId pokemon, boolean corrected, PokemonType tera) {
    }

    private record Correction(PokemonId detected, PokemonId chosen) {
    }

    private record Tera(PokemonId pokemon, PokemonType type) {
    }

    private List<Sighting> sightings = List.of();
    private final Map<Integer, Correction> corrections = new HashMap<>();
    private final List<PokemonId> added = new ArrayList<>();
    private final Map<Integer, Tera> scannedTera = new HashMap<>();
    private final List<PokemonType> addedTera = new ArrayList<>();
    private BattleMonitor.State lastState = BattleMonitor.State.WAITING;

    void update(BattleMonitor.State state, List<Sighting> newSightings) {
        if (state == BattleMonitor.State.WAITING && lastState == BattleMonitor.State.IN_BATTLE) {
            corrections.clear(); // the battle is over
            added.clear();
            scannedTera.clear();
            addedTera.clear();
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
            PokemonId pokemon = corrected ? correction.chosen() : sighting.pokemon();
            Tera tera = scannedTera.get(i);
            entries.add(new Entry(i, false, sighting.text(), pokemon, corrected,
                    tera != null && tera.pokemon().equals(pokemon) ? tera.type() : null));
        }
        for (int i = 0; i < added.size(); i++) {
            entries.add(new Entry(i, true, "", added.get(i), false, addedTera.get(i)));
        }
        return entries;
    }

    void change(Entry entry, PokemonId chosen) {
        if (entry.added()) {
            added.set(entry.index(), chosen);
            addedTera.set(entry.index(), null);
        } else {
            corrections.put(entry.index(), new Correction(sightings.get(entry.index()).pokemon(), chosen));
        }
    }

    void add(PokemonId pokemon) {
        added.add(pokemon);
        addedTera.add(null);
    }

    /** @param type the Tera type, null to undo */
    void terastallize(Entry entry, PokemonType type) {
        if (entry.added()) {
            addedTera.set(entry.index(), type);
        } else if (type == null) {
            scannedTera.remove(entry.index());
        } else {
            scannedTera.put(entry.index(), new Tera(entry.pokemon(), type));
        }
    }

    void remove(Entry entry) {
        if (!entry.added()) {
            throw new IllegalArgumentException("Only added Pokémon can be removed");
        }
        added.remove(entry.index());
        addedTera.remove(entry.index());
    }

    void clear() {
        sightings = List.of();
        corrections.clear();
        added.clear();
        scannedTera.clear();
        addedTera.clear();
    }
}
