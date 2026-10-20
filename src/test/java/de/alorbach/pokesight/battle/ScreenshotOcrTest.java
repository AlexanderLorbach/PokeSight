package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end OCR on real battle screenshots (downloaded at build time, sources in {@code testScreenshots} in
 * {@code build.gradle.kts}), enlarged the way emulators show them, cropped to the area a user would select.
 * Gen 4 in detail (every window size, sharp and smoothed); all games: {@link GameScreenshotOcrTest}.
 */
class ScreenshotOcrTest {

    private static final Ruleset GEN4 = RulesetLoader.load("gen4");
    /** Where the opponents' name boxes are on the DS top screen (256 x 192), as a user would select them. */
    private static final Rectangle SINGLE_AREA = new Rectangle(0, 16, 130, 34);
    private static final Rectangle DOUBLE_AREA = new Rectangle(0, 0, 130, 52);

    private static final Map<Language, BattleScanner> SCANNERS = new EnumMap<>(Language.class);
    private static final Map<Language, TesseractTextRecognizer> RECOGNIZERS = new EnumMap<>(Language.class);

    @BeforeAll
    static void startOcr() throws IOException {
        for (Language language : Language.values()) {
            TesseractTextRecognizer recognizer = new TesseractTextRecognizer(language);
            RECOGNIZERS.put(language, recognizer);
            SCANNERS.put(language, new BattleScanner(recognizer, new NameMatcher(GEN4, language), true)); // Gen 4 writes names in capitals
        }
    }

    @AfterAll
    static void stopOcr() {
        RECOGNIZERS.values().forEach(TesseractTextRecognizer::close);
    }

    /** Emulator window sizes 1x to 4x, sharp and with melonDS's default screen filter (bilinear smoothing). */
    static Stream<Arguments> emulatorViews() {
        return Stream.of(
                Arguments.of(1, false), Arguments.of(2, false), Arguments.of(3, false), Arguments.of(4, false),
                Arguments.of(2, true), Arguments.of(3, true), Arguments.of(4, true));
    }

    @ParameterizedTest(name = "{0}x smooth={1}")
    @MethodSource("emulatorViews")
    void readsEnglishSingleBattle(int scale, boolean smooth) throws IOException {
        assertThat(scan("pt-en-single-electabuzz.png", SINGLE_AREA, scale, smooth, Language.EN))
                .containsExactly(Optional.of(PokemonId.of(125))); // Electabuzz
    }

    @ParameterizedTest(name = "{0}x smooth={1}")
    @MethodSource("emulatorViews")
    void readsEnglishDoubleBattle(int scale, boolean smooth) throws IOException {
        assertThat(scan("pt-en-double-gabite-azumarill.png", DOUBLE_AREA, scale, smooth, Language.EN))
                .containsExactly(Optional.of(PokemonId.of(444)), Optional.of(PokemonId.of(184))); // Gabite, Azumarill
    }

    @ParameterizedTest(name = "{0}x smooth={1}")
    @MethodSource("emulatorViews")
    void readsGermanDoubleBattle(int scale, boolean smooth) throws IOException {
        assertThat(scan("dp-de-double-schaloko-waumpel.png", DOUBLE_AREA, scale, smooth, Language.DE))
                .containsExactly(Optional.of(PokemonId.of(266)), Optional.of(PokemonId.of(265))); // Schaloko, Waumpel
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pt-en-trainer-intro.png", "hgss-en-tag-battle-intro.png", "pt-en-attack-animation.png"})
    void findsNoNameWhereNoNameBoxIsShown(String file) throws IOException {
        assertThat(scan(file, DOUBLE_AREA, 2, false, Language.EN)).isEmpty();
    }

    private static List<Optional<PokemonId>> scan(String file, Rectangle area, int scale, boolean smooth, Language language) throws IOException {
        URL resource = ScreenshotOcrTest.class.getResource("/screenshots/" + file);
        assumeTrue(resource != null, () -> file + " was not downloaded (see testScreenshots in build.gradle.kts)");
        BufferedImage screenshot = ImageIO.read(resource);
        // Some files show both DS screens or a slightly larger frame; the battle is on the top 256 x 192.
        BufferedImage topScreen = screenshot.getSubimage(0, 0, Math.min(256, screenshot.getWidth()), Math.min(192, screenshot.getHeight()));
        BufferedImage window = enlarge(topScreen, scale, smooth);
        BufferedImage selection = window.getSubimage(area.x * scale, area.y * scale, area.width * scale, area.height * scale);
        return SCANNERS.get(language).scan(selection).stream().map(Sighting::pokemon).toList();
    }

    private static BufferedImage enlarge(BufferedImage image, int scale, boolean smooth) {
        BufferedImage result = new BufferedImage(image.getWidth() * scale, image.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        graphics.dispose();
        return result;
    }
}
