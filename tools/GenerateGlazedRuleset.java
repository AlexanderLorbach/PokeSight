import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the ruleset of Pokémon Glazed (Emerald hack by Lucbui/redriders180 and TrainerX493, v9.1). The hack is
 * binary-only (no source code, no data sheet), so the ruleset is built from what its documentation states, on top of
 * the PokeAPI-generated reference rulesets:
 *
 * <ul>
 *   <li>Roster and types: the fan wiki's table of all 411 Pokémon slots ("Pokemon Locations and Availability",
 *       pinned revision), checked against the reference types. Glazed replaced Emerald slots with Gen 4-6 Pokémon
 *       and has fixed slots for Mega Evolutions, Kyurem-B/W and two event battle Pokémon (keys 5000 + wiki slot).</li>
 *   <li>Type chart: Gen 6+ with Fairy ("Steel no longer resists Dark and Ghost", "Fairy-type included").</li>
 *   <li>Stats: Gen VII standard (Beta 8.0), so the gen7 ruleset; Zorua/Zoroark +15 Attack (Beta 8.2).</li>
 *   <li>Abilities: Emerald's engine knows only the Gen 3 abilities and no hidden ones: Gen 1-3 Pokémon keep their
 *       Emerald abilities (gen3 ruleset), later ones get the non-hidden ones of their Gen 3 pool (an assumption,
 *       no source lists them; the hack's custom trade abilities are unknown).</li>
 *   <li>Moves: Gen 1-3 moves plus the later moves the v9.0 move document lists as in the game, with Gen VII
 *       power (Beta 8.0) and the physical/special split (Beta 5E); Rapid Spin 50 (Beta 8.6.2).</li>
 * </ul>
 *
 * <p>Usage: {@code java tools/GenerateGlazedRuleset.java src/main/resources/rulesets/gen3 src/main/resources/rulesets/gen7 src/main/resources/rulesets/glazed}
 */
public final class GenerateGlazedRuleset {

    private static final String WIKI_TABLE = "https://fanmadepokemonglazed.fandom.com/api.php?action=parse&oldid=11462"
            + "&prop=wikitext&format=json&formatversion=2";
    private static final String WIKI_PAGE = "https://fanmadepokemonglazed.fandom.com/wiki/Pokemon_Locations_and_Availability?oldid=11462";
    private static final String MOVES_DOC = "https://docs.google.com/document/d/1zOY1BAz_6lnLWt7d4HQZwo4_6b7E-eMyNzq9eAk4J-I/export?format=txt";
    private static final String POKEAPI_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/e5b79b2e15d7c50735a945936afd4cd1cfd94eae/data/v2/csv/";
    private static final String USER_AGENT = "PokeSight ruleset generator (https://github.com/AlexanderLorbach/PokeSight)";

    private static final int OWN_KEY_BASE = 5000;
    private static final int LAST_GEN3_SPECIES = 386;
    private static final int LAST_GEN3_MOVE = 354;
    private static final int NAME_LENGTH = 10;

    /** Wiki spellings that differ from the Pokémon's name. */
    private static final Map<String, String> WIKI_NAMES = Map.of("Frosslass", "Froslass", "Deoxys (Attack Forme)", "Deoxys");
    /** The wiki row repeats the row above (Skorupi Poison/Bug): Drapion keeps Poison/Dark. */
    private static final Set<String> WIKI_TYPE_ERRORS = Set.of("Drapion");

    /** Glazed's own slots: wiki name -> PokeAPI form (stats, types, abilities, sprite). */
    private static final Map<String, String> OWN_SLOTS = Map.ofEntries(
            Map.entry("Scarfed Pikachu", "pikachu"), Map.entry("Enraged Mew", "mew"),
            Map.entry("Kyurem-B", "kyurem-black"), Map.entry("Kyurem-W", "kyurem-white"),
            Map.entry("Mega Venusaur", "venusaur-mega"), Map.entry("Mega Charizard X", "charizard-mega-x"),
            Map.entry("Mega Charizard Y", "charizard-mega-y"), Map.entry("Mega Blastoise", "blastoise-mega"),
            Map.entry("Mega Lucario", "lucario-mega"), Map.entry("Mega Salamence", "salamence-mega"),
            Map.entry("Mega Absol", "absol-mega"), Map.entry("Mega Manectric", "manectric-mega"));
    /** Stats the changelog gives (HP, Atk, Def, SpA, SpD, Spe): event battle Pokémon (Beta 8.2), Zorua/Zoroark. */
    private static final Map<String, List<Integer>> STATS = Map.of(
            "Scarfed Pikachu", List.of(80, 100, 80, 100, 80, 120), "Enraged Mew", List.of(205, 150, 65, 150, 80, 100));
    private static final Map<Integer, int[]> ATTACK = Map.of(570, new int[]{65, 80}, 571, new int[]{105, 120}); // dex -> {was, is}
    /** "Kyurem has a second ability - a second copy of Pressure" (Beta 8.7); its fused forms have no Gen 3 ability. */
    private static final List<String> KYUREM_ABILITIES = List.of("pressure");
    private static final Map<Integer, Integer> MOVE_POWER = Map.of(229, 50); // Rapid Spin, Beta 8.6.2

    private static final Pattern ROW_START = Pattern.compile("#(\\d{3})");
    private static final Pattern COLSPAN = Pattern.compile("\\s*colspan=\"(\\d)\"\\s*\\|(.*)");
    private static final Pattern SPECIES = Pattern.compile("^  (\\d+): \\{ name: \\{ (?:DE: \"[^\"]*\", )?EN: \"([^\"]*)\" }, "
            + "types: \\[([^]]*)], stats: \\[([^]]*)], abilities: \\[([^]]*)]");
    private static final Pattern MOVE = Pattern.compile("^  (\\d+): \\{ name: \\{ (?:DE: \"[^\"]*\", )?EN: \"([^\"]*)\" }, (type: .*)$");
    private static final Pattern ABILITY = Pattern.compile("^  ([a-z0-9-]+): \\{ (?:DE: \"[^\"]*\", )?EN: \"([^\"]*)\" }");
    private static final Pattern TYPE_NAME = Pattern.compile("^  ([A-Z]+): \\{ (?:DE: \"[^\"]*\", )?EN: \"([^\"]*)\" }");
    private static final Pattern POWER = Pattern.compile("power: \\d+");

    private record Reference(int key, String name, List<String> types, List<Integer> stats, List<String> abilities) {}

    private record Slot(int number, String name, List<String> types) {}

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, List<String[]>> csvCache = new HashMap<>();
    private final Path gen3;
    private final Path gen7;

    private GenerateGlazedRuleset(Path gen3, Path gen7) {
        this.gen3 = gen3;
        this.gen7 = gen7;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java tools/GenerateGlazedRuleset.java <gen3 ruleset> <gen7 ruleset> <output dir>");
            System.exit(1);
        }
        GenerateGlazedRuleset generator = new GenerateGlazedRuleset(Path.of(args[0]), Path.of(args[1]));
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        String movesDoc = generator.download(MOVES_DOC);
        String header = "# Generated by tools/GenerateGlazedRuleset.java from the Pokémon Glazed fan wiki (" + WIKI_PAGE
                + "), the v9.0 move document (" + MOVES_DOC + ", SHA-256 " + sha256(movesDoc) + "), the changelog and"
                + " the gen3/gen7 rulesets (PokeAPI) - do not edit by hand.\n";
        Map<String, String> abilityNames = new TreeMap<>();
        Files.writeString(output.resolve("pokemon.yaml"), generator.pokemonYaml(header, abilityNames), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("abilities.yaml"), generator.abilitiesYaml(header, abilityNames), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("moves.yaml"), generator.movesYaml(header, movesDoc), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("type-chart.yaml"), generator.typeChartYaml(header), StandardCharsets.UTF_8);
        System.out.println("Ruleset written to " + output.toAbsolutePath());
    }

    // ---------------------------------------------------------------- Pokémon

    private String pokemonYaml(String header, Map<String, String> abilityNames) throws IOException, InterruptedException {
        Map<Integer, Reference> gen7Species = references(gen7.resolve("pokemon.yaml"));
        Map<Integer, Reference> gen3Species = references(gen3.resolve("pokemon.yaml"));
        Map<String, Reference> byName = new HashMap<>();
        gen7Species.values().forEach(species -> byName.put(species.name(), species));
        Map<String, String> gen3Abilities = names(gen3.resolve("abilities.yaml"), ABILITY);
        Map<String, String> gen7Abilities = names(gen7.resolve("abilities.yaml"), ABILITY);

        Map<Integer, String> lines = new TreeMap<>();
        for (Slot slot : slots()) {
            String name = WIKI_NAMES.getOrDefault(slot.name(), slot.name());
            String form = OWN_SLOTS.get(name);
            int key;
            String speciesName;
            List<String> types;
            List<Integer> stats;
            List<String> abilities;
            String sprite = null;
            if (form != null) {
                int pokemonId = Integer.parseInt(pokemonId(form));
                Reference base = byName.get(baseName(form, byName));
                key = OWN_KEY_BASE + slot.number();
                speciesName = name; // the in-game name of these slots is unknown (10 letters at most)
                types = formTypes(pokemonId);
                stats = STATS.getOrDefault(name, formStats(pokemonId));
                abilities = form.startsWith("kyurem") ? KYUREM_ABILITIES
                        : pokemonId < 10000 ? gen3Species.get(base.key()).abilities()
                        : gen3Pool(formAbilities(pokemonId), gen3Abilities);
                sprite = String.valueOf(pokemonId);
            } else {
                Reference reference = byName.get(name);
                if (reference == null) {
                    throw new IllegalStateException("Wiki slot #" + slot.number() + " " + slot.name() + " matches no Pokémon");
                }
                key = reference.key();
                speciesName = name;
                types = reference.types();
                stats = new ArrayList<>(reference.stats());
                int[] attack = ATTACK.get(key);
                if (attack != null) {
                    if (stats.get(1) != attack[0]) {
                        throw new IllegalStateException(name + " Attack " + stats.get(1) + ", the changelog says " + attack[0]);
                    }
                    stats.set(1, attack[1]);
                }
                Set<String> hidden = key <= LAST_GEN3_SPECIES ? Set.of() : hiddenAbilities(key);
                abilities = key <= LAST_GEN3_SPECIES ? gen3Species.get(key).abilities()
                        : gen3Pool(reference.abilities().stream().filter(a -> !hidden.contains(a)).toList(), gen3Abilities);
                if (speciesName.length() > NAME_LENGTH) {
                    throw new IllegalStateException(speciesName + " is longer than " + NAME_LENGTH + " letters");
                }
            }
            if (!new LinkedHashSet<>(types).equals(new LinkedHashSet<>(slot.types())) && !WIKI_TYPE_ERRORS.contains(name)) {
                throw new IllegalStateException(name + ": wiki types " + slot.types() + ", data " + types);
            }
            if (lines.containsKey(key)) {
                throw new IllegalStateException("Two slots for " + name);
            }
            abilities.forEach(slug -> abilityNames.put(slug, gen3Abilities.getOrDefault(slug, gen7Abilities.get(slug))));
            StringBuilder line = new StringBuilder("{ name: { EN: ").append(quote(speciesName)).append(" }, types: ")
                    .append(types).append(", stats: ").append(stats).append(", abilities: ").append(abilities);
            if (sprite != null) {
                line.append(", sprite: ").append(quote(sprite));
            }
            lines.put(key, line.append(" }").toString());
        }
        System.out.println("  " + lines.size() + " Pokémon (" + lines.keySet().stream().filter(k -> k >= OWN_KEY_BASE).count()
                + " own slots)");

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Key: national dex number; ").append(OWN_KEY_BASE).append(" + the wiki's slot number for Glazed's own slots")
                .append(" (Mega Evolutions, Kyurem-B/W, event battle Pokémon; sprite: their PokeAPI sprite).\n");
        yaml.append("# stats: Gen VII (Zorua/Zoroark Attack +15). abilities: Gen 3 engine - Gen 1-3 Pokémon as in Emerald,")
                .append(" later ones their non-hidden Gen 3 abilities (assumed).\n");
        yaml.append("pokemon:\n");
        lines.forEach((key, line) -> yaml.append("  ").append(key).append(": ").append(line).append('\n'));
        return yaml.toString();
    }

    /** The wiki table: one row per slot (number, name, one or two types). */
    private List<Slot> slots() throws IOException, InterruptedException {
        String json = download(WIKI_TABLE);
        int start = json.indexOf("\"wikitext\":\"");
        if (start < 0) {
            throw new IllegalStateException("No wikitext in " + WIKI_TABLE);
        }
        String wikitext = unescapeJson(json.substring(start + "\"wikitext\":\"".length(), json.lastIndexOf('"')));
        List<Slot> slots = new ArrayList<>();
        for (String row : wikitext.split("\n\\|-")) {
            List<String> cells = new ArrayList<>();
            for (String line : row.strip().split("\n")) {
                if (!line.startsWith("|") || line.startsWith("|}")) {
                    continue;
                }
                String cell = line.substring(1);
                Matcher span = COLSPAN.matcher(cell);
                int count = 1;
                if (span.matches()) {
                    count = Integer.parseInt(span.group(1));
                    cell = span.group(2);
                }
                for (int i = 0; i < count; i++) {
                    cells.add(cell.replace("[[", "").replace("]]", "").strip());
                }
            }
            Matcher number = cells.isEmpty() ? null : ROW_START.matcher(cells.getFirst());
            if (number == null || !number.matches() || cells.size() < 4 || cells.get(2).equals("?")) {
                continue; // header, MissingNo
            }
            List<String> types = new ArrayList<>(new LinkedHashSet<>(List.of(cells.get(2).toUpperCase(), cells.get(3).toUpperCase())));
            types.remove("");
            slots.add(new Slot(Integer.parseInt(number.group(1)), cells.get(1), types));
        }
        if (slots.size() != 411) {
            throw new IllegalStateException("Expected 411 slots in the wiki table, found " + slots.size());
        }
        return slots;
    }

    private static String baseName(String form, Map<String, Reference> byName) {
        String base = form.split("-")[0];
        return byName.keySet().stream().filter(name -> name.equalsIgnoreCase(base)).findFirst().orElseThrow();
    }

    private static List<String> gen3Pool(List<String> abilities, Map<String, String> gen3Abilities) {
        return abilities.stream().filter(gen3Abilities::containsKey).distinct().toList();
    }

    // ---------------------------------------------------------------- PokeAPI forms

    private String pokemonId(String identifier) throws IOException, InterruptedException {
        return csv("pokemon").stream().filter(row -> row[1].equals(identifier)).map(row -> row[0]).findFirst()
                .orElseThrow(() -> new IllegalStateException("PokeAPI has no " + identifier));
    }

    private List<Integer> formStats(int pokemonId) throws IOException, InterruptedException {
        Integer[] stats = new Integer[6];
        for (String[] row : csv("pokemon_stats")) { // pokemon_id,stat_id,base_stat,effort
            if (Integer.parseInt(row[0]) == pokemonId) {
                stats[Integer.parseInt(row[1]) - 1] = Integer.parseInt(row[2]);
            }
        }
        return List.of(stats);
    }

    private List<String> formTypes(int pokemonId) throws IOException, InterruptedException {
        Map<String, String> typeIds = new HashMap<>();
        csv("types").forEach(row -> typeIds.put(row[0], row[1].toUpperCase()));
        Map<Integer, String> types = new TreeMap<>();
        for (String[] row : csv("pokemon_types")) { // pokemon_id,type_id,slot
            if (Integer.parseInt(row[0]) == pokemonId) {
                types.put(Integer.parseInt(row[2]), typeIds.get(row[1]));
            }
        }
        return List.copyOf(types.values());
    }

    /** Non-hidden abilities of a PokeAPI form, in slot order. */
    private List<String> formAbilities(int pokemonId) throws IOException, InterruptedException {
        return abilities(pokemonId, false);
    }

    private Set<String> hiddenAbilities(int speciesId) throws IOException, InterruptedException {
        return Set.copyOf(abilities(speciesId, true));
    }

    private List<String> abilities(int pokemonId, boolean hidden) throws IOException, InterruptedException {
        Map<String, String> identifiers = new HashMap<>();
        csv("abilities").forEach(row -> identifiers.put(row[0], row[1]));
        Map<Integer, String> abilities = new TreeMap<>();
        for (String[] row : csv("pokemon_abilities")) { // pokemon_id,ability_id,is_hidden,slot
            if (Integer.parseInt(row[0]) == pokemonId && row[2].equals("1") == hidden) {
                abilities.put(Integer.parseInt(row[3]), identifiers.get(row[1]));
            }
        }
        return List.copyOf(abilities.values());
    }

    // ---------------------------------------------------------------- moves, abilities, type chart

    private String movesYaml(String header, String movesDoc) throws IOException {
        int start = movesDoc.indexOf("except for the following:");
        int end = movesDoc.indexOf("Furthermore");
        if (start < 0 || end < start) {
            throw new IllegalStateException("The move document no longer has the list of later moves");
        }
        Set<String> laterMoves = new LinkedHashSet<>();
        movesDoc.substring(start, end).lines().skip(1).map(String::strip).filter(line -> !line.isEmpty()).forEach(laterMoves::add);
        Set<String> unmatched = new LinkedHashSet<>(laterMoves);
        Map<Integer, String> moves = new TreeMap<>();
        for (String line : Files.readAllLines(gen7.resolve("moves.yaml"), StandardCharsets.UTF_8)) {
            Matcher move = MOVE.matcher(line);
            if (!move.find()) {
                continue;
            }
            int id = Integer.parseInt(move.group(1));
            String name = move.group(2);
            if (id > LAST_GEN3_MOVE && !laterMoves.contains(name)) {
                continue;
            }
            unmatched.remove(name);
            String rest = move.group(3);
            if (MOVE_POWER.containsKey(id)) {
                rest = POWER.matcher(rest).replaceFirst("power: " + MOVE_POWER.get(id));
            }
            moves.put(id, "{ name: { EN: " + quote(name) + " }, " + rest);
        }
        System.out.println("  " + moves.size() + " damaging moves; later moves without damage or fixed power: " + unmatched);

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Damaging moves with a fixed power: Gen 1-3 moves and the later ones the move document lists, with Gen VII\n");
        yaml.append("# power and the physical/special split; Rapid Spin 50 (Beta 8.6.2). Key: move id.\n");
        yaml.append("moves:\n");
        moves.forEach((id, move) -> yaml.append("  ").append(id).append(": ").append(move).append('\n'));
        return yaml.toString();
    }

    private String abilitiesYaml(String header, Map<String, String> names) {
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# The generation, which decides how abilities work (Emerald's engine), and the names of the abilities in pokemon.yaml.\n");
        yaml.append("generation: 3\n");
        yaml.append("abilities:\n");
        names.forEach((slug, name) -> yaml.append("  ").append(slug).append(": { EN: ").append(quote(name)).append(" }\n"));
        return yaml.toString();
    }

    /** The gen7 chart (Gen 6+ with Fairy), English type names only. */
    private String typeChartYaml(String header) throws IOException {
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Gen 6+ chart with Fairy: \"Steel no longer resists Dark and Ghost\" (Beta 8.2), \"Fairy-type included\".\n");
        boolean body = false;
        for (String line : Files.readAllLines(gen7.resolve("type-chart.yaml"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") && !body) {
                if (line.startsWith("# effectiveness")) {
                    yaml.append(line).append('\n');
                }
                continue;
            }
            body = true;
            Matcher type = TYPE_NAME.matcher(line);
            yaml.append(type.find() ? "  " + type.group(1) + ": { EN: " + quote(type.group(2)) + " }" : line).append('\n');
        }
        return yaml.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static Map<Integer, Reference> references(Path file) throws IOException {
        Map<Integer, Reference> species = new LinkedHashMap<>();
        for (String line : oneLinePerSpecies(Files.readAllLines(file, StandardCharsets.UTF_8))) {
            Matcher m = SPECIES.matcher(line);
            if (m.find()) {
                int key = Integer.parseInt(m.group(1));
                species.put(key, new Reference(key, m.group(2), split(m.group(3)),
                        split(m.group(4)).stream().map(Integer::parseInt).toList(), split(m.group(5))));
            }
        }
        return species;
    }

    /** Species with forms are written as a block ("  19:" + indented fields); joins the base form's fields into one line. */
    private static List<String> oneLinePerSpecies(List<String> lines) {
        List<String> result = new ArrayList<>();
        StringBuilder block = null;
        for (String line : lines) {
            if (line.matches("  \\d+:")) {
                block = new StringBuilder(line).append(" {");
                result.add(null); // filled when the block ends
            } else if (block != null && line.startsWith("    ") && !line.startsWith("      ")) {
                if (!line.strip().equals("forms:")) {
                    block.append(block.toString().endsWith("{") ? " " : ", ").append(line.strip());
                }
                result.set(result.size() - 1, block + " }");
            } else if (!line.startsWith("      ")) {
                block = null;
                result.add(line);
            }
        }
        return result.stream().filter(line -> line != null).toList();
    }

    private static Map<String, String> names(Path file, Pattern pattern) throws IOException {
        Map<String, String> names = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                names.put(m.group(1), m.group(2));
            }
        }
        return names;
    }

    private static List<String> split(String list) {
        return list.isBlank() ? List.of() : List.of(list.split(",\\s*"));
    }

    private List<String[]> csv(String file) throws IOException, InterruptedException {
        List<String[]> rows = csvCache.get(file);
        if (rows == null) {
            rows = download(POKEAPI_CSV + file + ".csv").lines().skip(1).filter(line -> !line.isBlank())
                    .map(line -> line.split(",", -1)).toList();
            csvCache.put(file, rows);
        }
        return rows;
    }

    private static String unescapeJson(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                result.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case 'r' -> result.append('\r');
                case 'u' -> {
                    result.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> result.append(next); // \" \\ \/
            }
        }
        return result.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Download of " + url + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }
}
