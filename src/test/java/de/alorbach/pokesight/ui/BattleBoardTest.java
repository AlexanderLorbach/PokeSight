package de.alorbach.pokesight.ui;

import de.alorbach.pokesight.battle.BattleMonitor.State;
import de.alorbach.pokesight.battle.PokemonId;
import de.alorbach.pokesight.battle.Sighting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BattleBoardTest {

    private static final PokemonId GENGAR = PokemonId.of(94);
    private static final PokemonId GOLBAT = PokemonId.of(42);
    private static final PokemonId ZUBAT = PokemonId.of(41);
    private static final Sighting MISREAD_AS_GOLBAT = new Sighting("GOLBAT Lv30", GOLBAT);

    private final BattleBoard board = new BattleBoard();

    @Test
    void correctionReplacesTheMisreadPokemon() {
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));

        board.change(board.entries().getFirst(), ZUBAT);

        assertThat(board.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.pokemon()).isEqualTo(ZUBAT);
            assertThat(entry.corrected()).isTrue();
            assertThat(entry.readText()).isEqualTo("GOLBAT Lv30");
        });
    }

    @Test
    void correctionStaysWhileTheSameMisreadIsShown() {
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));
        board.change(board.entries().getFirst(), ZUBAT);

        board.update(State.IN_BATTLE, List.of(new Sighting("GOLBAT Lv31", GOLBAT)));

        assertThat(board.entries()).singleElement().extracting(BattleBoard.Entry::pokemon).isEqualTo(ZUBAT);
    }

    @Test
    void correctionStopsWhenAnotherPokemonIsSentOut() {
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));
        board.change(board.entries().getFirst(), ZUBAT);

        board.update(State.IN_BATTLE, List.of(new Sighting("GENGAR Lv40", GENGAR)));

        assertThat(board.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.pokemon()).isEqualTo(GENGAR);
            assertThat(entry.corrected()).isFalse();
        });
    }

    @Test
    void addedPokemonFollowScannedOnesAndCanBeChangedOrRemoved() {
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));
        board.add(GENGAR);
        board.add(ZUBAT);

        board.change(board.entries().get(1), GOLBAT);
        board.remove(board.entries().get(2));

        assertThat(board.entries()).extracting(BattleBoard.Entry::pokemon, BattleBoard.Entry::added)
                .containsExactly(tuple(GOLBAT, false), tuple(GOLBAT, true));
    }

    @Test
    void endOfBattleForgetsCorrectionsAndAdditions() {
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));
        board.change(board.entries().getFirst(), ZUBAT);
        board.add(GENGAR);

        board.update(State.WAITING, List.of());
        board.update(State.IN_BATTLE, List.of(MISREAD_AS_GOLBAT));

        assertThat(board.entries()).singleElement().extracting(BattleBoard.Entry::pokemon).isEqualTo(GOLBAT);
    }

    @Test
    void additionsWhileWaitingSurviveStatusMessages() {
        board.add(GENGAR);

        board.update(State.WAITING, List.of()); // e.g. "Scan found no Pokémon"

        assertThat(board.entries()).singleElement().extracting(BattleBoard.Entry::pokemon).isEqualTo(GENGAR);
    }
}
