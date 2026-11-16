package de.alorbach.pokesight.matchup;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.PokemonType;
import de.alorbach.pokesight.ruleset.Species;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttackStyleTest {

    @Test
    void comparesAttackWithSpecialAttack() {
        assertThat(AttackStyle.of(form(130, 60))).isEqualTo(AttackStyle.PHYSICAL); // Scovillain-like
        assertThat(AttackStyle.of(form(65, 125))).isEqualTo(AttackStyle.SPECIAL);   // Gardevoir
        assertThat(AttackStyle.of(form(80, 80))).isEqualTo(AttackStyle.MIXED);
    }

    @Test
    void closeStatsAreMixed() {
        assertThat(AttackStyle.of(form(109, 100))).isEqualTo(AttackStyle.MIXED);    // less than 10 % apart
        assertThat(AttackStyle.of(form(110, 100))).isEqualTo(AttackStyle.PHYSICAL);
        assertThat(AttackStyle.of(form(100, 110))).isEqualTo(AttackStyle.SPECIAL);
    }

    private static Species.Form form(int attack, int specialAttack) {
        return new Species.Form(Map.of(Language.EN, "Test"), List.of(PokemonType.NORMAL), null, null,
                List.of(50, attack, 50, specialAttack, 50, 50));
    }
}
