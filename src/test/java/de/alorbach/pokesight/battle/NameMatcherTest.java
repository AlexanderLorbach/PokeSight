package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    private static final Ruleset GEN4 = RulesetLoader.load("gen4");
    private final NameMatcher english = new NameMatcher(GEN4, Language.EN);
    private final NameMatcher german = new NameMatcher(GEN4, Language.DE);

    @ParameterizedTest
    @CsvSource({
            "GENGAR, 94",
            "gengar, 94",
            "GENGAP, 94",       // one misread letter
            "G3NGAR, 94",       // unmapped digit: dropped, leaving a distance of 1
            "MR. MIME, 122",
            "FARFETCH'D, 83",
            "BIBAREL, 400",
            "B1BAREL, 400",     // 1 read for I
            "TENTACRUEL, 73",
    })
    void matchesEnglishNames(String text, int speciesId) {
        assertThat(english.match(text)).map(NameMatcher.Match::pokemon).contains(PokemonId.of(speciesId));
    }

    @ParameterizedTest
    @CsvSource({
            "GARADOS, 130",
            "BURMADAME, 413",
            "GLURAK, 6",
            "PANTIMOS, 122",
    })
    void matchesGermanNames(String text, int speciesId) {
        assertThat(german.match(text)).map(NameMatcher.Match::pokemon).contains(PokemonId.of(speciesId));
    }

    @Test
    void ignoresAccentsAndUmlautDots() {
        // OCR often loses the dots of umlauts in pixel fonts.
        assertThat(NameMatcher.normalize("Flegmon Ä ö ü")).isEqualTo("FLEGMONAOU");
        assertThat(NameMatcher.normalize("Nidoran♀")).isEqualTo("NIDORAN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "AB", "Lv", "WHAT WILL", "FIGHT", "BAG", "XYZXYZXYZ"})
    void rejectsTextThatIsNoName(String text) {
        assertThat(english.match(text)).isEmpty();
    }

    @Test
    void shortNamesMustMatchExactly() {
        assertThat(english.match("MEW")).map(NameMatcher.Match::pokemon).contains(PokemonId.of(151));
        assertThat(english.match("MEX")).isEmpty();
    }

    @Test
    void computesEditDistance() {
        assertThat(NameMatcher.ocrDistance("GENGAR", "GENGAR")).isZero();
        assertThat(NameMatcher.ocrDistance("GENGAP", "GENGAR")).isEqualTo(2);
        assertThat(NameMatcher.ocrDistance("GENAR", "GENGAR")).isEqualTo(2);
        assertThat(NameMatcher.ocrDistance("", "ABC")).isEqualTo(6);
        assertThat(NameMatcher.ocrDistance("SCHISSY", "SCHIGGY")).isEqualTo(2); // G/S look alike: half a letter each
    }

    @Test
    void matchesTheRegionalFormInGamesOfThatRegion() {
        Ruleset gen7 = RulesetLoader.load("gen7");
        assertThat(new NameMatcher(gen7, Language.EN, "alola").match("Meowth"))
                .map(NameMatcher.Match::pokemon).contains(new PokemonId(52, 1));    // Alolan Meowth
        assertThat(new NameMatcher(gen7, Language.EN, null).match("Meowth"))
                .map(NameMatcher.Match::pokemon).contains(PokemonId.of(52));
        assertThat(new NameMatcher(gen7, Language.DE, "alola").match("Bisasam"))
                .map(NameMatcher.Match::pokemon).contains(PokemonId.of(1));         // no regional form
    }
}
