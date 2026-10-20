package de.alorbach.pokesight.battle;

import de.alorbach.pokesight.game.GameCatalog;
import de.alorbach.pokesight.game.GameDefinition;
import de.alorbach.pokesight.ocr.TesseractTextRecognizer;
import de.alorbach.pokesight.ruleset.Language;
import de.alorbach.pokesight.ruleset.Ruleset;
import de.alorbach.pokesight.ruleset.RulesetLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OCR on real battle screenshots of every game, listed in {@code screenshots.tsv} (test resources) with the opponents
 * they show. Each screenshot is read with its game's definition (ruleset, capital names, region) and cropped to where
 * the name boxes are, as a user would select them.
 */
public class GameScreenshotOcrTest {

    /**
     * Where the opponents' names are, relative to the game screen (x, y, width, height). The games with floating
     * names are read as a whole: the scanner finds the labels itself.
     */
    public static final Map<String, Rectangle2D> AREAS = new HashMap<>();
    private static final List<String> FLOATING_NAMES = List.of("scarlet-violet", "legends-arceus", "legends-za");

    static {
        Rectangle2D gameBoy = new Rectangle2D.Double(0, 0, 0.75, 0.17);
        Rectangle2D gameBoyAdvance = new Rectangle2D.Double(0, 0, 0.6, 0.32);
        Rectangle2D ds = new Rectangle2D.Double(0, 0, 0.51, 0.27);
        Rectangle2D dsGen5 = new Rectangle2D.Double(0, 0.08, 0.6, 0.22);
        Rectangle2D threeDs = new Rectangle2D.Double(0, 0, 1, 0.14);
        Rectangle2D floating = new Rectangle2D.Double(0, 0, 1, 1);
        for (String id : List.of("red-blue", "yellow", "gold-silver", "crystal")) {
            AREAS.put(id, gameBoy);
        }
        for (String id : List.of("ruby-sapphire", "emerald", "firered-leafgreen")) {
            AREAS.put(id, gameBoyAdvance);
        }
        for (String id : List.of("diamond-pearl", "platinum", "heartgold-soulsilver")) {
            AREAS.put(id, ds);
        }
        AREAS.put("black-white", dsGen5);
        AREAS.put("black-2-white-2", dsGen5);
        for (String id : List.of("x-y", "omega-ruby-alpha-sapphire", "sun-moon", "ultra-sun-ultra-moon")) {
            AREAS.put(id, threeDs);
        }
        AREAS.put("sword-shield", new Rectangle2D.Double(0.6, 0, 0.4, 0.1));
        AREAS.put("brilliant-diamond-shining-pearl", new Rectangle2D.Double(0.7, 0, 0.3, 0.1));
        AREAS.put("lets-go", new Rectangle2D.Double(0.7, 0, 0.3, 0.09));
        FLOATING_NAMES.forEach(id -> AREAS.put(id, floating));
    }

    /** Native-resolution screenshots are enlarged like emulator windows (scale, smooth); Full HD ones are read as they are. */
    private static final List<int[]> SMALL_SCREEN_VIEWS = List.of(new int[]{1, 0}, new int[]{2, 1}, new int[]{3, 0});
    private static final int SMALL_SCREEN_MAX_WIDTH = 480;

    private static final Map<String, GameDefinition> GAMES = new HashMap<>();
    private static final Map<String, Ruleset> RULESETS = new HashMap<>();
    private static final Map<Language, TesseractTextRecognizer> RECOGNIZERS = new EnumMap<>(Language.class);

    public record Case(String file, String game, Language language, List<String> expected) {
        @Override
        public String toString() {
            return file;
        }
    }

    @BeforeAll
    static void startOcr() throws IOException {
        GameCatalog.load().forEach(game -> GAMES.put(game.id(), game));
        for (Language language : Language.values()) {
            RECOGNIZERS.put(language, new TesseractTextRecognizer(language));
        }
    }

    @AfterAll
    static void stopOcr() {
        RECOGNIZERS.values().forEach(TesseractTextRecognizer::close);
    }

    public static List<Case> cases() {
        try (InputStream in = Objects.requireNonNull(GameScreenshotOcrTest.class.getResourceAsStream("/screenshots.tsv"));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<Case> cases = new ArrayList<>();
            for (String line : reader.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields[5].startsWith(MoveReaderTest.MOVES)) {
                    continue; // a move list, read by MoveReaderTest
                }
                List<String> expected = fields[5].isEmpty() ? List.of() : Arrays.asList(fields[5].split("\\+"));
                cases.add(new Case(fields[0], fields[3], Language.valueOf(fields[4]), expected));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Minimum number of correctly read views per game (each screenshot at 1x, 2x smoothed and 3x; Full HD ones at
     * 1x only), as measured on 2026-10-20. Not every view is read: the tests document how well each game works and
     * fail when a change makes a game worse. Raise a value when the OCR gets better.
     */
    private static final Map<String, Integer> MIN_CORRECT = Map.ofEntries(
            Map.entry("red-blue", 33), Map.entry("yellow", 32), Map.entry("gold-silver", 27), Map.entry("crystal", 32),
            Map.entry("ruby-sapphire", 36), Map.entry("emerald", 36), Map.entry("firered-leafgreen", 24),
            Map.entry("diamond-pearl", 32), Map.entry("platinum", 42), Map.entry("heartgold-soulsilver", 31),
            Map.entry("black-white", 30), Map.entry("black-2-white-2", 27),
            Map.entry("x-y", 24), Map.entry("omega-ruby-alpha-sapphire", 29),
            Map.entry("sun-moon", 26), Map.entry("ultra-sun-ultra-moon", 19), Map.entry("lets-go", 5),
            Map.entry("sword-shield", 8), Map.entry("brilliant-diamond-shining-pearl", 6), Map.entry("legends-arceus", 4),
            Map.entry("scarlet-violet", 6), Map.entry("legends-za", 3));

    static Stream<String> games() {
        return cases().stream().map(Case::game).distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("games")
    void readsTheOpponentsOfEachGame(String gameId) throws IOException {
        GameDefinition game = Objects.requireNonNull(GAMES.get(gameId), gameId);
        Ruleset ruleset = RULESETS.computeIfAbsent(game.ruleset(), RulesetLoader::load);
        int correct = 0;
        int read = 0;
        List<String> misses = new ArrayList<>();
        for (Case screenshot : cases().stream().filter(c -> c.game().equals(gameId)).toList()) {
            URL resource = GameScreenshotOcrTest.class.getResource("/screenshots/" + screenshot.file());
            if (resource == null) {
                continue; // not downloaded (see screenshots.tsv); counted as missing below
            }
            BufferedImage image = ImageIO.read(resource);
            BattleScanner scanner = new BattleScanner(RECOGNIZERS.get(screenshot.language()),
                    new NameMatcher(ruleset, screenshot.language(), game.region()), game.capitalNames(), game.floatingNames());
            List<Optional<PokemonId>> expected = screenshot.expected().stream()
                    .map(name -> Optional.of(id(ruleset, screenshot.language(), name, game.region()))).toList();
            for (int[] view : image.getWidth() <= SMALL_SCREEN_MAX_WIDTH ? SMALL_SCREEN_VIEWS : List.of(new int[]{1, 0})) {
                List<Sighting> sightings = scanner.scan(enlarge(crop(image, AREAS.get(gameId)), view[0], view[1] == 1));
                read++;
                if (sightings.stream().map(Sighting::pokemon).toList().equals(expected)) {
                    correct++;
                } else {
                    misses.add(screenshot.file() + " " + view[0] + "x" + (view[1] == 1 ? " smooth" : "") + ": read "
                            + sightings.stream().map(Sighting::text).toList() + ", expected " + screenshot.expected());
                }
            }
        }
        assumeTrue(read > 0, () -> "No screenshot of " + gameId + " was downloaded (see screenshots.tsv)");
        System.out.printf("%s: %d of %d views read correctly%n", gameId, correct, read);
        int minimum = Math.min(MIN_CORRECT.getOrDefault(gameId, read), read);
        assertThat(correct).as("%s: correctly read views; misses:%n%s", gameId, String.join(System.lineSeparator(), misses))
                .isGreaterThanOrEqualTo(minimum);
    }

    /** The species with that name in the game's language, in the game's regional form if it has one. */
    private static PokemonId id(Ruleset ruleset, Language language, String name, String region) {
        String key = NameMatcher.normalize(name);
        return ruleset.species().entrySet().stream()
                .filter(entry -> NameMatcher.normalize(entry.getValue().name(language)).equals(key))
                .findFirst()
                .map(entry -> new PokemonId(entry.getKey(), entry.getValue().regionalForm(region)))
                .orElseThrow(() -> new IllegalArgumentException("No species named " + name));
    }

    /** The area of the game screen; files that show both DS screens are cut to the upper one first. */
    public static BufferedImage crop(BufferedImage image, Rectangle2D area) {
        int screenHeight = image.getHeight() > image.getWidth() ? image.getHeight() / 2 : image.getHeight();
        int x = (int) (area.getX() * image.getWidth());
        int y = (int) (area.getY() * screenHeight);
        int width = Math.min((int) Math.ceil(area.getWidth() * image.getWidth()), image.getWidth() - x);
        int height = Math.min((int) Math.ceil(area.getHeight() * screenHeight), screenHeight - y);
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.drawImage(image.getSubimage(x, y, width, height), 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    public static BufferedImage enlarge(BufferedImage image, int scale, boolean smooth) {
        if (scale == 1) {
            return image;
        }
        BufferedImage result = new BufferedImage(image.getWidth() * scale, image.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        graphics.dispose();
        return result;
    }
}
