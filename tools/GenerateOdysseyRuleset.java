import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generates the ruleset of Pokémon Odyssey (FireRed ROM hack by PacoScarso) from the developer's data workbook
 * "Pokémon stats, learnset, Etrian Variants etc." (linked on https://eeveeexpo.com/odyssey/).
 *
 * <p>Usage: {@code java tools/GenerateOdysseyRuleset.java <workbook.xlsx or URL> <referenceRuleset> <outputDir>}
 * <br>Example:
 * <pre>
 * java tools/GenerateOdysseyRuleset.java \
 *   "https://docs.google.com/spreadsheets/d/1Es1clPMUhEEqZRHW0tgAmvxqSzVXiXmD/export?format=xlsx" \
 *   src/main/resources/rulesets/gen9 src/main/resources/rulesets/odyssey
 * </pre>
 *
 * <p>From the workbook: the type chart (sheet "Type Chart": multipliers are cell colours, explained by the legend
 * below the chart; Aether replaces Fairy), every species' types, abilities and Odyssey base stats (sheets "#1-151" to
 * "Paradox"), the names of the dex slots (sheet "Pokédex") and the new and changed moves (sheet "New Moves &amp;
 * Abilities"; type and category are icons, recognized by their image hash).
 *
 * <p>The workbook does not list unchanged moves, so their power, type and category come from the reference ruleset
 * (gen9); Fairy moves are left out (the game has no Fairy type). Species keep their national dex number (Etrian
 * Variants replace their species); Pokémon without one (Gorochu, Yggdreon, Reefsola) and second versions of a species
 * (Battle Bond forms, Galarian Farfetch'd) get {@link #CUSTOM_KEY_BASE} + their dex slot.
 */
public class GenerateOdysseyRuleset {

    private static final String POKEAPI_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/";
    private static final String POKEAPI_ENGLISH = "9";
    private static final String LANGUAGE = "EN";
    /** Generation that decides how abilities work: the hack has modern mechanics (physical/special split, Gen 9 Pokémon). */
    private static final int GENERATION = 9;
    /** Keys of species without an own national dex number: this + their dex slot. */
    private static final int CUSTOM_KEY_BASE = 5000;
    /** Keys of moves that only exist in the hack: this + their position in the workbook. */
    private static final int NEW_MOVE_KEY_BASE = 20000;
    private static final List<String> SPECIES_SHEETS = List.of("#1-151", "#152-251", "#252-386", "4th Gen", "Paradox");
    /** Etrian Variants with a new name: name -> the species they replace (sheet "Etrian Variants": Rattata line). */
    private static final Map<String, String> RENAMED_VARIANTS = Map.of("ratreecate", "raticate");
    /** Second versions of a species, marked in the workbook's species names: suffix -> PokeAPI form suffix (null: base sprite). */
    private static final Map<String, String> VERSION_SUFFIXES = new LinkedHashMap<>();

    static {
        VERSION_SUFFIXES.put(" (battle bond)", null);
        VERSION_SUFFIXES.put(" (galar)", "galar");
    }

    /** Type icons of the moves sheet, by the first 16 hex digits of their SHA-256 (checked by eye). */
    private static final Map<String, String> TYPE_ICONS = Map.ofEntries(
            Map.entry("727289ba3bc826ea", "NORMAL"), Map.entry("66a6c37953d96f98", "FIGHTING"),
            Map.entry("697cbc4a7f3fb8fb", "FLYING"), Map.entry("1ce530b14adb65a6", "POISON"),
            Map.entry("ecfffcb5752de636", "GROUND"), Map.entry("11da3b89de2cdf21", "ROCK"),
            Map.entry("b78602ae7690bfc3", "BUG"), Map.entry("c3fbe273075770a2", "GHOST"),
            Map.entry("626d577388bfb784", "STEEL"), Map.entry("dd216bd8d4578f61", "FIRE"),
            Map.entry("bfbacf83e5284d86", "WATER"), Map.entry("5ff663042534a559", "GRASS"),
            Map.entry("0db3bf8d3dd73634", "ELECTRIC"), Map.entry("d17b735657d2cc81", "PSYCHIC"),
            Map.entry("2d071f21b6bab64e", "ICE"), Map.entry("a58366af51160023", "DRAGON"),
            Map.entry("027f7598b8d9edbb", "DARK"), Map.entry("839a4e683ecfb10f", "AETHER"));
    private static final Map<String, String> CATEGORY_ICONS = Map.of(
            "cfc7585d2a060c33", "PHYSICAL", "d31779f89a8d6a80", "SPECIAL", "a88c92facb8ffda0", "STATUS");
    private static final Map<String, String> LEGEND = Map.of(
            "no effect", "0", "not very effective", "0.5", "super effective", "2");
    /** Variable powers are 0 or 1 in the reference. */
    private static final int MAX_PLACEHOLDER_POWER = 1;
    private static final int STAT_COUNT = 6;
    /** Longest Pokémon name the game shows (FireRed engine). */
    private static final int MAX_NAME_LENGTH = 10;

    private static final Pattern REF_MOVE = Pattern.compile(
            "^ {2}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }, type: (\\w+), power: (\\d+), category: (\\w+) }");
    private static final Pattern REF_ABILITY = Pattern.compile("^ {2}([a-z0-9-]+): \\{.*EN: \"([^\"]*)\"");
    private static final Pattern CELL_REF = Pattern.compile("([A-Z]+)(\\d+)");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Workbook workbook;
    private final Path reference;
    private final String header;

    private GenerateOdysseyRuleset(String source, Path reference) throws Exception {
        byte[] bytes = source.startsWith("http") ? downloadBytes(source) : Files.readAllBytes(Path.of(source));
        this.workbook = new Workbook(bytes);
        this.reference = reference;
        this.header = "# Generated by tools/GenerateOdysseyRuleset.java from the developer's workbook (SHA-256 "
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ") - do not edit by hand.\n";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java tools/GenerateOdysseyRuleset.java <workbook.xlsx or URL> <referenceRuleset> <outputDir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[2]);
        Files.createDirectories(outputDir);
        GenerateOdysseyRuleset generator = new GenerateOdysseyRuleset(args[0], Path.of(args[1]));
        Map<String, String> typeNames = new LinkedHashMap<>();
        Files.writeString(outputDir.resolve("type-chart.yaml"), generator.typeChartYaml(typeNames), StandardCharsets.UTF_8);
        Map<String, String> abilityNames = new TreeMap<>();
        Files.writeString(outputDir.resolve("pokemon.yaml"), generator.pokemonYaml(typeNames.keySet(), abilityNames), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("moves.yaml"), generator.movesYaml(typeNames.keySet()), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("abilities.yaml"), generator.abilitiesYaml(abilityNames), StandardCharsets.UTF_8);
        System.out.println("Ruleset written to " + outputDir.toAbsolutePath());
    }

    // ---------------------------------------------------------------- type chart

    private String typeChartYaml(Map<String, String> typeNames) {
        Sheet sheet = workbook.sheet("Type Chart");
        // Header row: the defending types from "Normal" to the right; the attacking types below the cell left of it.
        int[] normal = sheet.find(text -> text.equals("Normal"));
        int headerRow = normal[0];
        int firstColumn = normal[1];
        List<String> defenders = new ArrayList<>();
        for (int column = firstColumn; !sheet.text(headerRow, column).isEmpty(); column++) {
            defenders.add(sheet.text(headerRow, column));
        }
        Map<String, String> legend = new HashMap<>(); // fill colour -> multiplier
        sheet.cells().forEach((position, cell) -> LEGEND.forEach((label, multiplier) -> {
            if (cell.text().toLowerCase(Locale.ROOT).startsWith(label)) {
                legend.put(sheet.firstFillLeftOf(position[0], position[1]), multiplier);
            }
        }));
        if (legend.size() != LEGEND.size()) {
            throw new IllegalStateException("Type chart legend not found: " + legend);
        }
        defenders.forEach(type -> typeNames.put(type.toUpperCase(Locale.ROOT), type));

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# effectiveness: attacking type -> defending type -> multiplier. Pairs not listed are 1x.\n");
        yaml.append("# Aether is the hack's own type; there is no Fairy type.\n");
        yaml.append("types:\n");
        typeNames.forEach((key, name) -> yaml.append("  ").append(key).append(": ").append(nameMap(name)).append('\n'));
        yaml.append("effectiveness:\n");
        for (int i = 0; i < defenders.size(); i++) {
            int row = headerRow + 1 + i;
            String attacker = sheet.text(row, firstColumn - 1);
            if (!attacker.equals(defenders.get(i))) {
                throw new IllegalStateException("Attacking type " + attacker + " in row " + row + ", expected " + defenders.get(i));
            }
            List<String> entries = new ArrayList<>();
            for (int j = 0; j < defenders.size(); j++) {
                String fill = sheet.fill(row, firstColumn + j);
                if (fill != null) {
                    String multiplier = legend.get(fill);
                    if (multiplier == null) {
                        throw new IllegalStateException("Colour " + fill + " of " + attacker + " vs " + defenders.get(j) + " is not in the legend");
                    }
                    entries.add(defenders.get(j).toUpperCase(Locale.ROOT) + ": " + multiplier);
                }
            }
            yaml.append("  ").append(attacker.toUpperCase(Locale.ROOT)).append(": { ").append(String.join(", ", entries)).append(" }\n");
        }
        return yaml.toString();
    }

    // ---------------------------------------------------------------- Pokémon

    private record Block(String name, List<String> types, List<String> abilities, List<Integer> stats) {
    }

    private String pokemonYaml(Set<String> types, Map<String, String> abilityNames) throws IOException, InterruptedException {
        List<Block> blocks = new ArrayList<>();
        for (String sheetName : SPECIES_SHEETS) {
            blocks.addAll(blocks(workbook.sheet(sheetName), types));
        }
        Map<String, String> referenceAbilities = referenceAbilitySlugs();
        Map<String, Integer> nationalByName = new HashMap<>();
        for (String line : csvLines("pokemon_species_names")) {
            String[] values = line.split(",", 4); // pokemon_species_id,local_language_id,name,genus
            if (values.length >= 3 && values[1].equals(POKEAPI_ENGLISH)) {
                nationalByName.put(normalize(values[2]), Integer.parseInt(values[0]));
            }
        }
        Map<String, String> speciesIdentifiers = columns("pokemon_species", "id", "identifier");
        Map<String, String> pokemonIds = columns("pokemon", "identifier", "id");

        // Dex slots (sheet "Pokédex") -> the block of that Pokémon
        Sheet dex = workbook.sheet("Pokédex");
        int[] headerCell = dex.find(text -> text.equals("Dex ID"));
        Map<Integer, String> slotNames = new LinkedHashMap<>();
        for (int row = headerCell[0] + 1; !dex.text(row, headerCell[1]).isEmpty(); row++) {
            slotNames.put(Integer.parseInt(dex.text(row, headerCell[1])), dex.text(row, headerCell[1] + 1));
        }
        Map<Integer, Block> slots = matchSlots(slotNames, blocks);

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Key: national dex number (Etrian Variants replace their species); ").append(CUSTOM_KEY_BASE)
                .append(" + dex slot for Pokémon without one\n# and second versions of a species. name: as in the developer's Pokédex list.\n");
        yaml.append("# sprite: sprite of a species whose key is no dex number (\"none\": the hack's own Pokémon, no sprite).\n");
        yaml.append("# stats: HP, Attack, Defense, Sp. Atk, Sp. Def, Speed (Odyssey's values).\n");
        yaml.append("pokemon:\n");
        Map<Integer, String> written = new TreeMap<>();
        for (Map.Entry<Integer, Block> entry : slots.entrySet()) {
            int slot = entry.getKey();
            Block block = entry.getValue();
            String lower = block.name().toLowerCase(Locale.ROOT);
            String suffix = VERSION_SUFFIXES.keySet().stream().filter(lower::endsWith).findFirst().orElse(null);
            String baseName = suffix == null ? lower : lower.substring(0, lower.length() - suffix.length());
            Integer national = nationalNumber(baseName, nationalByName);
            int key;
            String sprite = null;
            if (national == null) {
                key = CUSTOM_KEY_BASE + slot;
                sprite = "none";
            } else if (suffix != null) {
                key = CUSTOM_KEY_BASE + slot;
                String form = VERSION_SUFFIXES.get(suffix);
                sprite = form == null ? String.valueOf(national) : pokemonIds.get(speciesIdentifiers.get(String.valueOf(national)) + "-" + form);
                if (sprite == null) {
                    throw new IllegalStateException("PokeAPI has no " + form + " form of #" + national);
                }
            } else {
                key = national;
            }
            List<String> abilities = new ArrayList<>();
            for (String ability : block.abilities()) {
                String slug = referenceAbilities.getOrDefault(normalize(ability), ability.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
                abilityNames.putIfAbsent(slug, ability);
                abilities.add(slug);
            }
            String name = displayName(slotNames.get(slot), block.name());
            if (written.put(key, name) != null) {
                throw new IllegalStateException("Two Pokémon with the key " + key + ": " + written.get(key) + ", " + name);
            }
            yaml.append("  ").append(key).append(": { name: ").append(nameMap(name))
                    .append(", types: ").append(block.types()).append(", stats: ").append(block.stats())
                    .append(", abilities: [").append(String.join(", ", abilities)).append(']');
            if (sprite != null) {
                yaml.append(", sprite: ").append(quote(sprite));
            }
            yaml.append(" }\n");
            if (key != slot) {
                System.out.println("  slot " + slot + " " + name + " -> " + key + (sprite != null ? " (sprite " + sprite + ")" : ""));
            }
        }
        return yaml.toString();
    }

    /** The species blocks of a sheet: name, "Type:", "Ability:" rows; stats in a separate table found by name. */
    private static List<Block> blocks(Sheet sheet, Set<String> types) {
        Map<String, List<List<Integer>>> stats = new HashMap<>();
        sheet.cells().forEach((position, cell) -> {
            int row = position[0];
            int column = position[1];
            if (cell.text().equals("HP") && sheet.text(row, column + 1).equals("ATK")) {
                if (!sheet.text(row + 1, column + STAT_COUNT + 1).equals("Odyssey")) {
                    throw new IllegalStateException("No Odyssey stats below " + sheet.text(row - 1, column));
                }
                List<Integer> values = new ArrayList<>();
                for (int i = 0; i < STAT_COUNT; i++) {
                    values.add((int) Double.parseDouble(sheet.text(row + 1, column + i)));
                }
                stats.computeIfAbsent(speciesName(sheet.text(row - 1, column)), k -> new ArrayList<>()).add(values);
            }
        });
        List<Block> blocks = new ArrayList<>();
        sheet.cells().forEach((position, cell) -> {
            if (!cell.text().equals("Type:")) {
                return;
            }
            int row = position[0];
            int column = position[1];
            String name = speciesName(sheet.text(row - 1, column));
            List<String> blockTypes = new ArrayList<>();
            for (String type : sheet.text(row, column + 1).split("/")) {
                String key = type.trim().toUpperCase(Locale.ROOT);
                if (!types.contains(key)) {
                    throw new IllegalStateException(name + ": unknown type " + type);
                }
                blockTypes.add(key);
            }
            List<String> abilities = new ArrayList<>();
            for (String ability : sheet.text(row + 1, column + 1).split("/")) {
                if (!ability.isBlank() && !abilities.contains(ability.trim())) {
                    abilities.add(ability.trim());
                }
            }
            List<List<Integer>> candidates = stats.get(name);
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalStateException("No stats for " + name + " in " + sheet.name());
            }
            blocks.add(new Block(name, List.copyOf(blockTypes), List.copyOf(abilities), candidates.removeFirst()));
        });
        return blocks;
    }

    /** "⭐ MEGANIUM" -> "MEGANIUM" (the star marks Etrian and regional variants). */
    private static String speciesName(String cell) {
        return cell.replaceFirst("^[^\\p{L}\\d]+", "").trim();
    }

    /**
     * Matches the dex slots to the species blocks: same name; else "B.B. X" = "X (BATTLE BOND)"; else a cut name
     * ("Flut. Mane" = Flutter Mane: its letters in order); else one wrong letter ("Claydal" = Claydol); else a name cut
     * to the game's 10 letters with a changed last letter ("Dudunspars" = Dudunsparce).
     */
    private static Map<Integer, Block> matchSlots(Map<Integer, String> slotNames, List<Block> blocks) {
        Map<Integer, Block> slots = new TreeMap<>();
        List<Block> unmatched = new ArrayList<>(blocks);
        List<java.util.function.BiPredicate<String, String>> rules = List.of(
                String::equals,
                (slot, block) -> slot.startsWith("bb") && block.equals(slot.substring(2) + "battlebond"),
                GenerateOdysseyRuleset::isSubsequence,
                (slot, block) -> slot.length() == block.length() && distance(slot, block) == 1,
                (slot, block) -> slot.length() >= MAX_NAME_LENGTH - 1 && block.startsWith(slot.substring(0, slot.length() - 1)));
        for (var rule : rules) {
            for (Map.Entry<Integer, String> slot : slotNames.entrySet()) {
                if (slots.containsKey(slot.getKey())) {
                    continue;
                }
                String wanted = normalize(slot.getValue());
                List<Block> found = unmatched.stream().filter(block -> rule.test(wanted, normalize(block.name()))).toList();
                if (found.size() == 1) {
                    slots.put(slot.getKey(), found.getFirst());
                    unmatched.remove(found.getFirst());
                }
            }
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalStateException("Species without a dex slot: " + unmatched.stream().map(Block::name).toList());
        }
        slotNames.forEach((slot, name) -> {
            if (!slots.containsKey(slot)) {
                System.out.println("  slot " + slot + " " + name + ": no data in the workbook, left out");
            }
        });
        return slots;
    }

    private static Integer nationalNumber(String name, Map<String, Integer> nationalByName) {
        String wanted = normalize(RENAMED_VARIANTS.getOrDefault(normalize(name), name));
        Integer exact = nationalByName.get(wanted);
        if (exact != null) {
            return exact;
        }
        // Odyssey drops letters of some names ("Sandy Shock" = Sandy Shocks)
        List<Integer> prefixed = nationalByName.entrySet().stream().filter(entry -> entry.getKey().startsWith(wanted)).map(Map.Entry::getValue).toList();
        return prefixed.size() == 1 ? prefixed.getFirst() : null;
    }

    /** The slot list's name, unless it differs from the species sheet by one letter only (a typo: "Claydal"). */
    private static String displayName(String slotName, String blockName) {
        String slot = normalize(slotName);
        String block = normalize(blockName);
        if (slot.length() == block.length() && distance(slot, block) == 1) {
            return titleCase(blockName);
        }
        return slotName;
    }

    // ---------------------------------------------------------------- moves

    private record WorkbookMove(String name, String type, String category, String power) {
    }

    private String movesYaml(Set<String> types) throws IOException {
        Map<String, Integer> referenceIds = new HashMap<>();
        Map<Integer, String[]> moves = new TreeMap<>(); // id -> name, type, power, category
        for (String line : Files.readAllLines(reference.resolve("moves.yaml"), StandardCharsets.UTF_8)) {
            Matcher move = REF_MOVE.matcher(line);
            if (move.find()) {
                int id = Integer.parseInt(move.group(1));
                referenceIds.put(normalize(move.group(2)), id);
                moves.put(id, new String[]{move.group(2), move.group(3), move.group(4), move.group(5)});
            }
        }
        Map<String, String> learnsetNames = learnsetMoveNames();
        List<WorkbookMove> workbookMoves = workbookMoves();
        Set<String> changed = new LinkedHashSet<>();
        for (int i = 0; i < workbookMoves.size(); i++) {
            WorkbookMove move = workbookMoves.get(i);
            Integer id = referenceIds.get(normalize(move.name()));
            boolean damaging = !move.category().equals("STATUS") && move.power().matches("\\d+(\\.0)?")
                    && (int) Double.parseDouble(move.power()) > MAX_PLACEHOLDER_POWER;
            if (id == null && !damaging) {
                continue; // a new status move or one of variable power
            }
            int key = id != null ? id : NEW_MOVE_KEY_BASE + i;
            if (!damaging) {
                moves.remove(key);
                changed.add(move.name() + " (no fixed power)");
                continue;
            }
            if (!types.contains(move.type())) {
                throw new IllegalStateException(move.name() + ": unknown type " + move.type());
            }
            String name = learnsetNames.getOrDefault(normalize(move.name()), id != null ? moves.get(id)[0] : titleCase(move.name()));
            moves.put(key, new String[]{name, move.type(), String.valueOf((int) Double.parseDouble(move.power())), move.category()});
            if (id != null) {
                changed.add(name);
            }
        }
        List<String> dropped = new ArrayList<>();
        moves.entrySet().removeIf(entry -> {
            if (!types.contains(entry.getValue()[1])) {
                dropped.add(entry.getValue()[0]);
                return true;
            }
            return false;
        });
        System.out.println("  moves changed by the workbook: " + changed);
        System.out.println("  reference moves of a type the hack lacks: " + dropped);

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Damaging moves with a fixed power. Key: move id (PokeAPI); ").append(NEW_MOVE_KEY_BASE)
                .append("+ for the hack's own moves.\n# The workbook's new and changed moves; all others as in the reference ruleset (gen9), without Fairy moves.\n");
        yaml.append("moves:\n");
        moves.forEach((id, move) -> {
            String name = id < NEW_MOVE_KEY_BASE ? learnsetNames.getOrDefault(normalize(move[0]), move[0]) : move[0];
            yaml.append("  ").append(id).append(": { name: ").append(nameMap(name)).append(", type: ").append(move[1])
                    .append(", power: ").append(move[2]).append(", category: ").append(move[3]).append(" }\n");
        });
        return yaml.toString();
    }

    /** Spellings of the learnsets, the game's own (12 letters at most: "Ancientpower"), by normalized name. */
    private Map<String, String> learnsetMoveNames() {
        Map<String, String> names = new HashMap<>();
        for (String sheetName : SPECIES_SHEETS) {
            Sheet sheet = workbook.sheet(sheetName);
            sheet.cells().forEach((position, cell) -> {
                if (cell.text().matches("LV\\.\\s*\\d+")) {
                    String move = sheet.text(position[0], position[1] + 1);
                    if (!move.isEmpty()) {
                        names.putIfAbsent(normalize(move), move);
                    }
                }
            });
        }
        return names;
    }

    /** Moves of the sheet "New Moves & Abilities": blocks of name, "Type" (icon), "Category" (icon), "Power". */
    private List<WorkbookMove> workbookMoves() {
        Sheet sheet = workbook.sheet("New Moves & Abilities");
        List<WorkbookMove> moves = new ArrayList<>();
        List<String> unknownIcons = new ArrayList<>();
        sheet.cells().forEach((position, cell) -> {
            int row = position[0];
            int column = position[1];
            if (!cell.text().equals("Type") || !sheet.text(row + 1, column).equals("Category")) {
                return;
            }
            String name = sheet.text(row - 1, column);
            String type = TYPE_ICONS.get(sheet.image(row, column + 1));
            String category = CATEGORY_ICONS.get(sheet.image(row + 1, column + 1));
            if (type == null || category == null) {
                unknownIcons.add(name + " " + sheet.image(row, column + 1) + "/" + sheet.image(row + 1, column + 1));
                return;
            }
            moves.add(new WorkbookMove(name, type, category, sheet.text(row + 2, column + 1)));
        });
        if (!unknownIcons.isEmpty()) {
            throw new IllegalStateException("Unknown type or category icons (look at them and extend the maps): " + unknownIcons);
        }
        return moves;
    }

    // ---------------------------------------------------------------- abilities

    private Map<String, String> referenceAbilitySlugs() throws IOException {
        Map<String, String> slugs = new HashMap<>();
        for (String line : Files.readAllLines(reference.resolve("abilities.yaml"), StandardCharsets.UTF_8)) {
            Matcher ability = REF_ABILITY.matcher(line);
            if (ability.find()) {
                slugs.put(normalize(ability.group(2)), ability.group(1));
            }
        }
        return slugs;
    }

    private String abilitiesYaml(Map<String, String> names) {
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# The generation, which decides how abilities work, and the names of the abilities in pokemon.yaml.\n");
        yaml.append("# Identifiers as in PokeAPI where the ability exists there; the hack's own abilities have none of the effects PokeSight knows.\n");
        yaml.append("generation: ").append(GENERATION).append('\n');
        yaml.append("abilities:\n");
        names.forEach((slug, name) -> yaml.append("  ").append(slug).append(": ").append(nameMap(name)).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("♀", "f").replace("♂", "m").replaceAll("[^a-z0-9]", "");
    }

    private static boolean isSubsequence(String part, String whole) {
        int i = 0;
        for (int j = 0; j < whole.length() && i < part.length(); j++) {
            if (part.charAt(i) == whole.charAt(j)) {
                i++;
            }
        }
        return i == part.length() && !part.isEmpty();
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    /** "CALL: SNAKE" -> "Call: Snake", "5-RING SWORD" -> "5-Ring Sword". */
    private static String titleCase(String text) {
        StringBuilder result = new StringBuilder();
        boolean wordStart = true;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(wordStart && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            wordStart = c == ' ' || c == '-';
        }
        return result.toString();
    }

    private static String nameMap(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Missing name");
        }
        return "{ " + LANGUAGE + ": " + quote(name) + " }";
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private Map<String, String> columns(String file, String keyColumn, String valueColumn) throws IOException, InterruptedException {
        List<String> lines = csvLines(file);
        List<String> header = List.of(lines.getFirst().split(","));
        int key = header.indexOf(keyColumn);
        int value = header.indexOf(valueColumn);
        Map<String, String> columns = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",");
            if (values.length > Math.max(key, value)) {
                columns.put(values[key], values[value]);
            }
        }
        return columns;
    }

    private List<String> csvLines(String file) throws IOException, InterruptedException {
        return new String(downloadBytes(POKEAPI_CSV + file + ".csv"), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
    }

    private byte[] downloadBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download of " + url + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    // ---------------------------------------------------------------- minimal xlsx reader

    private record Cell(String text, String fill) {
    }

    /** One worksheet: cell texts and fill colours by {row, column} (1-based), images by their top-left cell. */
    private record Sheet(String name, Map<List<Integer>, Cell> grid, Map<List<Integer>, String> images) {

        String text(int row, int column) {
            Cell cell = grid.get(List.of(row, column));
            return cell == null ? "" : cell.text();
        }

        String fill(int row, int column) {
            Cell cell = grid.get(List.of(row, column));
            return cell == null ? null : cell.fill();
        }

        /** Hash prefix of the image anchored at the cell, or null. */
        String image(int row, int column) {
            return images.get(List.of(row, column));
        }

        /** All cells as {row, column} -> cell, ordered by row, then column. */
        Map<int[], Cell> cells() {
            Map<int[], Cell> cells = new TreeMap<>((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            grid.forEach((position, cell) -> cells.put(new int[]{position.get(0), position.get(1)}, cell));
            return cells;
        }

        int[] find(java.util.function.Predicate<String> test) {
            return cells().entrySet().stream().filter(entry -> test.test(entry.getValue().text())).map(Map.Entry::getKey)
                    .findFirst().orElseThrow(() -> new IllegalStateException("Cell not found in " + name));
        }

        String firstFillLeftOf(int row, int column) {
            for (int c = column - 1; c >= 1; c--) {
                if (fill(row, c) != null) {
                    return fill(row, c);
                }
            }
            throw new IllegalStateException("No colour left of row " + row + ", column " + column + " in " + name);
        }
    }

    private static final class Workbook {

        private final Map<String, byte[]> entries = new HashMap<>();
        private final List<String> sharedStrings = new ArrayList<>();
        private final List<String> cellFills = new ArrayList<>(); // style index -> fill colour (ARGB) or null
        private final Map<String, String> sheetFiles = new LinkedHashMap<>(); // sheet name -> xl/worksheets/sheetN.xml

        Workbook(byte[] xlsx) throws Exception {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            if (entries.containsKey("xl/sharedStrings.xml")) {
                for (Element si : elements(xml("xl/sharedStrings.xml").getDocumentElement(), "si")) {
                    sharedStrings.add(texts(si));
                }
            }
            Element styles = xml("xl/styles.xml").getDocumentElement();
            List<String> fills = new ArrayList<>();
            for (Element fill : elements(child(styles, "fills"), "fill")) {
                Element pattern = child(fill, "patternFill");
                Element color = pattern == null ? null : child(pattern, "fgColor");
                boolean solid = pattern != null && "solid".equals(pattern.getAttribute("patternType"));
                fills.add(solid && color != null && !color.getAttribute("rgb").isEmpty() ? color.getAttribute("rgb") : null);
            }
            for (Element xf : elements(child(styles, "cellXfs"), "xf")) {
                String fillId = xf.getAttribute("fillId");
                cellFills.add(fillId.isEmpty() ? null : fills.get(Integer.parseInt(fillId)));
            }
            Map<String, String> targets = relationships("xl/_rels/workbook.xml.rels", "xl/");
            for (Element sheet : elements(child(xml("xl/workbook.xml").getDocumentElement(), "sheets"), "sheet")) {
                sheetFiles.put(sheet.getAttribute("name"), targets.get(sheet.getAttribute("r:id")));
            }
        }

        Sheet sheet(String name) {
            String file = sheetFiles.get(name);
            if (file == null) {
                throw new IllegalStateException("The workbook has no sheet " + name + " (sheets: " + sheetFiles.keySet() + ")");
            }
            try {
                Map<List<Integer>, Cell> grid = new HashMap<>();
                for (Element c : elements(xml(file).getDocumentElement(), "c")) {
                    Matcher ref = CELL_REF.matcher(c.getAttribute("r"));
                    if (!ref.matches()) {
                        continue;
                    }
                    String style = c.getAttribute("s");
                    String fill = style.isEmpty() ? null : cellFills.get(Integer.parseInt(style));
                    Element v = child(c, "v");
                    String text = switch (c.getAttribute("t")) {
                        case "s" -> v == null ? "" : sharedStrings.get(Integer.parseInt(v.getTextContent()));
                        case "inlineStr" -> texts(c);
                        default -> v == null ? "" : v.getTextContent();
                    };
                    if (!text.isEmpty() || fill != null) {
                        grid.put(List.of(Integer.parseInt(ref.group(2)), column(ref.group(1))), new Cell(text.trim(), fill));
                    }
                }
                return new Sheet(name, grid, images(file));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read sheet " + name, e);
            }
        }

        /** Images of the sheet's drawing by their top-left cell (1-based) -> SHA-256 prefix of the image file. */
        private Map<List<Integer>, String> images(String sheetFile) throws Exception {
            Map<List<Integer>, String> images = new HashMap<>();
            String sheetRels = sheetFile.replace("worksheets/", "worksheets/_rels/") + ".rels";
            if (!entries.containsKey(sheetRels)) {
                return images;
            }
            for (String drawing : relationships(sheetRels, "xl/worksheets/").values()) {
                String drawingRels = drawing.replace("drawings/", "drawings/_rels/") + ".rels";
                if (!drawing.contains("drawings/") || !entries.containsKey(drawingRels)) {
                    continue; // no drawing, or one without images (charts, shapes)
                }
                Map<String, String> media = relationships(drawingRels, "xl/drawings/");
                NodeList anchors = xml(drawing).getDocumentElement().getChildNodes();
                for (int i = 0; i < anchors.getLength(); i++) {
                    if (!(anchors.item(i) instanceof Element anchor)) {
                        continue;
                    }
                    Element from = child(anchor, "xdr:from");
                    List<Element> blips = new ArrayList<>();
                    collect(anchor, "a:blip", blips);
                    if (from == null || blips.isEmpty()) {
                        continue;
                    }
                    int column = Integer.parseInt(child(from, "xdr:col").getTextContent()) + 1;
                    int row = Integer.parseInt(child(from, "xdr:row").getTextContent()) + 1;
                    byte[] image = entries.get(media.get(blips.getFirst().getAttribute("r:embed")));
                    if (image != null) {
                        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)).substring(0, 16);
                        images.put(List.of(row, column), hash);
                    }
                }
            }
            return images;
        }

        /** Relationship id -> target path inside the archive. */
        private Map<String, String> relationships(String file, String baseDirectory) throws Exception {
            Map<String, String> targets = new HashMap<>();
            for (Element rel : elements(xml(file).getDocumentElement(), "Relationship")) {
                String target = rel.getAttribute("Target");
                Path resolved = target.startsWith("/") ? Path.of(target.substring(1)) : Path.of(baseDirectory).resolve(target).normalize();
                targets.put(rel.getAttribute("Id"), resolved.toString().replace('\\', '/'));
            }
            return targets;
        }

        private Document xml(String file) throws Exception {
            byte[] content = entries.get(file);
            if (content == null) {
                throw new IllegalStateException("The workbook has no " + file);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        }

        private static int column(String letters) {
            int column = 0;
            for (char c : letters.toCharArray()) {
                column = column * 26 + (c - 'A' + 1);
            }
            return column;
        }

        private static String texts(Element element) {
            List<Element> texts = new ArrayList<>();
            collect(element, "t", texts);
            StringBuilder text = new StringBuilder();
            texts.forEach(t -> text.append(t.getTextContent()));
            return text.toString();
        }

        private static List<Element> elements(Element parent, String tag) {
            List<Element> found = new ArrayList<>();
            collect(parent, tag, found);
            return found;
        }

        private static void collect(Node node, String tag, List<Element> found) {
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element) {
                    if (element.getTagName().equals(tag)) {
                        found.add(element);
                    }
                    collect(element, tag, found);
                }
            }
        }

        private static Element child(Element parent, String tag) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && element.getTagName().equals(tag)) {
                    return element;
                }
            }
            return null;
        }
    }
}
