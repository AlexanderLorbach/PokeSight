package de.alorbach.pokesight.ruleset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The damaging moves one Pokémon learns in one game, by how it learns them (see {@code learnsets/<id>.yaml}).
 *
 * @param level    move id -> level it is learned at; 0 means when the Pokémon evolves (Gen 7 on)
 * @param machine  moves taught by a TM, HM or TR
 * @param tutor    moves taught by a move tutor
 * @param egg      egg moves: only by breeding
 * @param reminder moves only the move reminder teaches (Scarlet/Violet, Legends: Z-A)
 * @param forms    form index -> a form's own learnset where it differs (Alolan Raichu); other forms learn these
 */
public record Learnset(Map<Integer, Integer> level, List<Integer> machine, List<Integer> tutor, List<Integer> egg,
                       List<Integer> reminder, Map<Integer, Learnset> forms) {

    /** How a move is learned, easiest first. */
    public enum Method { LEVEL, MACHINE, TUTOR, REMINDER, EGG }

    /**
     * One way to learn a move.
     *
     * @param level the level for {@link Method#LEVEL}; 0 for the others
     */
    public record Way(Method method, int level) {
    }

    public Learnset {
        level = level == null ? Map.of() : Map.copyOf(level);
        machine = machine == null ? List.of() : List.copyOf(machine);
        tutor = tutor == null ? List.of() : List.copyOf(tutor);
        egg = egg == null ? List.of() : List.copyOf(egg);
        reminder = reminder == null ? List.of() : List.copyOf(reminder);
        forms = forms == null ? Map.of() : Map.copyOf(forms);
    }

    /** This form's learnset: its own one, or the species' (this one). */
    public Learnset form(int form) {
        return forms.getOrDefault(form, this);
    }

    /** Every move it learns, with all ways to learn it, easiest first. */
    public Map<Integer, List<Way>> ways() {
        Map<Integer, List<Way>> ways = new LinkedHashMap<>();
        level.forEach((moveId, atLevel) -> add(ways, moveId, new Way(Method.LEVEL, atLevel)));
        machine.forEach(moveId -> add(ways, moveId, new Way(Method.MACHINE, 0)));
        tutor.forEach(moveId -> add(ways, moveId, new Way(Method.TUTOR, 0)));
        reminder.forEach(moveId -> add(ways, moveId, new Way(Method.REMINDER, 0)));
        egg.forEach(moveId -> add(ways, moveId, new Way(Method.EGG, 0)));
        return ways;
    }

    private static void add(Map<Integer, List<Way>> ways, int moveId, Way way) {
        ways.computeIfAbsent(moveId, key -> new ArrayList<>()).add(way);
    }
}
