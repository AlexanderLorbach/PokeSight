package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.game.GameCatalog;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.game.GameRulesets;
import de.alorbach.pokesight.ocr.GlyphFont;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Name boxes of games without wiki screenshots, cut from the player's own screenshots as the selected areas: kept out
 * of Git (© Nintendo; local-screenshots/ is ignored). Put the file there to run its case.
 */
@Tag("ocr") // real OCR on screenshots: run by gradlew ocrTest, not test
class LocalBattleScreenshotOcrTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "", value = {
            // file | game id | the player's team (species, own boxes) or empty (opponents) | learned letters file or
            // empty (learned from this image only) | expected species
            // Swellow's W looks like an H: read with the W learned while playing, then by fewer variants than Dodrio alone.
            "platinum-together-en-double-swellow-dodrio.png | platinum-together | | platinum-together-glyphs.txt | 277+85",
            // Feraligatr's name is yellow (its turn); Gengar is the ally's, not in the team.
            "platinum-together-en-own-double-feraligatr-gengar.png | platinum-together | 281+907+467+633+603+160 | | 160+94",
    })
    void readsTheNameBoxes(String file, String gameId, String team, String letters, String expected) throws IOException {
        Path path = Path.of("local-screenshots", file);
        assumeTrue(Files.exists(path), () -> path + " is missing (a local screenshot, not in Git)");
        Path lettersFile = letters == null ? null : Path.of("local-screenshots", letters);
        assumeTrue(lettersFile == null || Files.exists(lettersFile), () -> lettersFile + " is missing (not in Git)");
        GameDefinition game = GameCatalog.load().stream().filter(candidate -> candidate.id().equals(gameId)).findFirst().orElseThrow();
        Ruleset ruleset = GameRulesets.load(game, Map.of());
        Set<Integer> preferred = team == null ? Set.of() : species(team).stream().collect(Collectors.toSet());

        try (TesseractTextRecognizer recognizer = new TesseractTextRecognizer(Language.EN)) {
            // As in PokeSight: letter shapes learned from the names read exactly, from the first scan on.
            BattleScanner scanner = new BattleScanner(recognizer, new NameMatcher(ruleset, Language.EN, game.region()),
                    game.capitalNames(), game.floatingNames(), lettersFile == null ? new GlyphFont() : GlyphFont.load(lettersFile),
                    game.nameColors());
            BufferedImage image = ImageIO.read(path.toFile());
            scanner.scan(image, preferred);

            assertThat(scanner.scan(image, preferred))
                    .extracting(sighting -> sighting.pokemon().speciesId())
                    .containsExactlyElementsOf(species(expected));
        }
    }

    private static List<Integer> species(String list) {
        return Arrays.stream(list.split("\\+")).map(String::trim).map(Integer::valueOf).toList();
    }
}
