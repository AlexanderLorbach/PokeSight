import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a ruleset for a ROM hack built on the pokeemerald decompilation (with the battle engine expansion) from
 * the hack's own source files.
 *
 * <p>Usage: {@code java tools/GeneratePokeemeraldRuleset.java <hackSource> <typeChartSource> <referenceRuleset> <outputDir>}
 * <br>Example (Pokémon R.O.W.E.: the developer's up-to-date data repository; its type chart only in the last full
 * source release, 1.9.4):
 * <pre>
 * java tools/GeneratePokeemeraldRuleset.java \
 *   https://raw.githubusercontent.com/BelialClover/RoweRepo/e596e740cffbfb1d18e9bbd1ee2bd6a757f478c8 \
 *   https://raw.githubusercontent.com/BelialClover/RoweSource/292eab691310e8b0cdb91a3905c34105261e24e1 \
 *   src/main/resources/rulesets/gen9 src/main/resources/rulesets/rowe
 * </pre>
 * Pokémon Emerald Rogue, EX edition v2.1 (tag EX-v2.1; newer expansion layout, type chart in the same source):
 * <pre>
 * java tools/GeneratePokeemeraldRuleset.java \
 *   https://raw.githubusercontent.com/Pokabbie/pokeemerald-rogue/559792325f500d480b892799218ee015962f93a2 \
 *   https://raw.githubusercontent.com/Pokabbie/pokeemerald-rogue/559792325f500d480b892799218ee015962f93a2 \
 *   src/main/resources/rulesets/gen9 src/main/resources/rulesets/emerald-rogue
 * </pre>
 * Sources are base URLs or local directories.
 *
 * <p>Newer expansions (1.7+) are detected by {@code src/data/pokemon/species_info.h}: configuration in
 * {@code include/config.h} and {@code include/config/*.h}, one entry per species and form in
 * {@code species_info/gen_N.h} (types, stats, abilities, name, dex number, {@code FRONT_PIC}). Their shared-field
 * macros (also with parameters, {@code ROTOM_APPLIANCE_INFO(form)}) are expanded and conditional values evaluated.
 *
 * <p>From the hack: {@code include/constants/*.h} (types, species, configuration), {@code src/data/pokemon/base_stats.h}
 * (types, base stats, abilities), {@code src/data/battle_moves.h} (power, type, category), the in-game names in
 * {@code src/data/text/} and the battle sprites ({@code src/data/pokemon_graphics/front_pic_table.h} ->
 * {@code graphics/pokemon/...png}, checked to exist); the type chart from {@code src/battle_util.c} of the second
 * source. The reference ruleset (generated from PokeAPI) supplies form keys, names and regions, type names and the
 * move ids; dex numbers and moves are checked against PokeAPI identifiers. Moves that exist in PokeAPI but not in the
 * reference (Z-Moves, Max Moves, variable power) are left out; moves PokeAPI does not know are the hack's own.
 *
 * <p>Writes {@code type-chart.yaml}, {@code pokemon.yaml}, {@code moves.yaml} and {@code abilities.yaml}, English only
 * (English build: {@code GAME_LANGUAGE} = {@code LANGUAGE_ENGLISH}).
 */
public class GeneratePokeemeraldRuleset {

    private static final String POKEAPI_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/";
    private static final String LANGUAGE = "EN";
    /** PokeAPI's local_language_id of English. */
    private static final String ENGLISH_ID = "9";
    private static final String NAME_KEY = "name:";
    /** Keys of moves that only exist in the hack: this + the hack's move id. */
    private static final int NEW_MOVE_KEY_BASE = 20000;
    /** Variable powers are 0 or 1 (as in PokeAPI). */
    private static final int MAX_PLACEHOLDER_POWER = 1;
    /** What a regular English build of a battle engine expansion hack defines (the config headers do the rest). */
    private static final Map<String, String> BUILD_DEFINES = Map.of(
            "BATTLE_ENGINE", "1", "ITEM_EXPANSION", "1", "LANGUAGE_ENGLISH", "2", "LANGUAGE_SPANISH", "7", "GAME_LANGUAGE", "LANGUAGE_ENGLISH");
    private static final List<String> CONFIG_FILES = List.of("include/constants/pokemon_config.h", "include/constants/battle_config.h");
    /** Configuration of newer expansions (1.7+), whose Pokémon data is in {@link #SPECIES_INFO} instead of base_stats.h. */
    private static final List<String> EXPANSION_CONFIG_FILES = List.of(
            "include/config.h", "include/config/battle.h", "include/config/pokemon.h", "include/config/species_enabled.h");
    private static final String SPECIES_INFO = "src/data/pokemon/species_info.h";
    /** Newer expansions name regional forms SPECIES_RATTATA_ALOLAN; PokeAPI says rattata-alola. */
    private static final Map<String, String> REGION_TOKENS = Map.of("alolan", "alola", "galarian", "galar", "hisuian", "hisui", "paldean", "paldea");
    /** The type no Pokémon has (Curse in Gen 2-4). */
    private static final String MYSTERY_TYPE = "MYSTERY";
    /** Types no Pokémon has: MYSTERY and the Terastal-only Stellar type (left out like in the reference). */
    private static final Set<String> HIDDEN_TYPES = Set.of(MYSTERY_TYPE, "STELLAR");
    /** Ability constants whose PokeAPI identifier is neither their name nor unique by English name ("As One" twice). */
    private static final Map<String, String> ABILITY_ALIASES = Map.of(
            "ABILITY_AS_ONE_ICE_RIDER", "as-one-glastrier", "ABILITY_AS_ONE_SHADOW_RIDER", "as-one-spectrier");
    /** Move constants with an English name from before Gen 6 that PokeAPI no longer knows. */
    private static final Map<String, String> OLD_MOVE_IDENTIFIERS = Map.of("faint-attack", "feint-attack");
    private static final Map<String, String> CATEGORIES = Map.of("SPLIT_PHYSICAL", "PHYSICAL", "SPLIT_SPECIAL", "SPECIAL");
    /** Battle-only forms the reference leaves out as well. */
    private static final Pattern BATTLE_ONLY_FORM = Pattern.compile("_(MEGA|GMAX|GIGANTAMAX|PRIMAL)(_|$)");
    private static final String NO_SPRITE_SYMBOL = "gMonFrontPic_CircledQuestionMark";
    private static final String SPRITE_PATH = "graphics/pokemon/{name}.png";
    private static final List<String> STATS = List.of("baseHP", "baseAttack", "baseDefense", "baseSpAttack", "baseSpDefense", "baseSpeed");

    private static final Pattern DEFINE = Pattern.compile("#define\\s+(\\w+)\\s+([^/\\n]+?)\\s*(?://.*)?$");
    private static final Pattern ENTRY = Pattern.compile("\\n\\s*\\[(\\w+)]\\s*=\\s*");
    private static final Pattern NAME_ENTRY = Pattern.compile("\\[(\\w+)]\\s*=\\s*_\\(\"([^\"]*)\"\\)");
    /** A row of the type table; newer expansions write 1x as {@code ______}. */
    private static final Pattern TYPE_ROW = Pattern.compile("\\{((?:\\s*(?:X\\([0-9.]+\\)|_{6}),?)+)\\s*}");
    private static final Pattern MULTIPLIER = Pattern.compile("X\\(([0-9.]+)\\)|_{6}");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include \"(species_info/\\w+\\.h)\"\\s*$");
    private static final Pattern SPECIES_NAME = Pattern.compile("\\.speciesName\\s*=\\s*_\\(\"([^\"]*)\"\\)");
    /** {@code FRONT_PIC(Bulbasaur, 40, 40)} or written out: {@code .frontPic = gMonFrontPic_Archaludon}. */
    private static final Pattern SPECIES_FRONT_PIC = Pattern.compile("\\bFRONT_PIC\\((\\w+),|\\.frontPic\\s*=\\s*gMonFrontPic_(\\w+)");
    private static final Pattern DEX_NUMBER = Pattern.compile("\\.natDexNum\\s*=\\s*(\\w+)");
    private static final Pattern MACRO_NAME = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");
    private static final Pattern FUNCTION_DEFINE = Pattern.compile("#define\\s+(\\w+)\\(([^)]*)\\)\\s+(.+)$");
    /** Function-like macros worth expanding: those that carry species data (not FRONT_PIC, ICON, ...). */
    private static final Pattern SPECIES_DATA = Pattern.compile("\\.(base\\w+|types|abilities|speciesName|natDexNum)\\s*=|FRONT_PIC\\(");
    private static final Pattern FRONT_PIC = Pattern.compile("SPECIES_SPRITE\\((\\w+),\\s*(\\w+)\\)");
    private static final Pattern PIC_FILE = Pattern.compile("const u32 (\\w+)\\[]\\s*=\\s*INCBIN_U32\\(\"graphics/pokemon/([a-z0-9_/]+)\\.4bpp\\.lz\"\\)");
    // Reference ruleset lines (see GenerateRuleset's output)
    private static final Pattern REF_TYPE = Pattern.compile("^ {2}([A-Z]+): \\{.*EN: \"([^\"]*)\"");
    private static final Pattern REF_MOVE = Pattern.compile("^ {2}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }, type: (\\w+), power: (\\d+), category: (\\w+) }");
    private static final Pattern REF_SPECIES = Pattern.compile("^ {2}(\\d+):(?: \\{ name: \\{.*?EN: \"([^\"]*)\")?");
    private static final Pattern REF_SPECIES_NAME = Pattern.compile("^ {4}name: \\{.*?EN: \"([^\"]*)\"");
    private static final Pattern REF_FORM = Pattern.compile(
            "^ {6}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }.*?sprite: \"([^\"]*)\"(?:, region: (\\w+))?");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String hackSource;
    private final String typeChartSource;
    private final Path reference;
    /** The output folder's name: prefix of the hack's own abilities. */
    private final String rulesetId;
    /** Name -> value as written (a number or another name): resolved when used, like the C preprocessor does. */
    private final Map<String, String> defines = new HashMap<>(BUILD_DEFINES);
    /** Other object-like macros (shared species fields such as {@code MR_MIME_MISC_INFO}, expressions): expanded in the text. */
    private final Map<String, String> macros = new HashMap<>();
    /** Function-like macros with species data ({@code ROTOM_APPLIANCE_INFO(form)}): name -> parameters and body. */
    private final Map<String, FunctionMacro> functionMacros = new HashMap<>();
    /** Newer expansion (1.7+): Pokémon data in species_info.h, configuration in include/config/. */
    private final boolean speciesInfoLayout;

    private GeneratePokeemeraldRuleset(String hackSource, String typeChartSource, Path reference, String rulesetId)
            throws IOException, InterruptedException {
        this.hackSource = hackSource;
        this.typeChartSource = typeChartSource;
        this.reference = reference;
        this.rulesetId = rulesetId;
        this.speciesInfoLayout = exists(hackSource, SPECIES_INFO);
        for (String file : speciesInfoLayout ? EXPANSION_CONFIG_FILES : CONFIG_FILES) {
            preprocess(read(hackSource, file)); // collects the #defines
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: java tools/GeneratePokeemeraldRuleset.java <hackSource> <typeChartSource> <referenceRuleset> <outputDir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[3]);
        Files.createDirectories(outputDir);
        GeneratePokeemeraldRuleset generator = new GeneratePokeemeraldRuleset(args[0], args[1], Path.of(args[2]), outputDir.getFileName().toString());
        String header = "# Generated by tools/GeneratePokeemeraldRuleset.java from the hack's sources (" + args[0]
                + ") and type chart (" + args[1] + ") - do not edit by hand.\n";

        List<String> types = generator.types();
        Map<String, String> abilityNames = new TreeMap<>();
        Files.writeString(outputDir.resolve("type-chart.yaml"), generator.typeChartYaml(header, types), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("pokemon.yaml"), generator.pokemonYaml(header, Set.copyOf(types), abilityNames), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("moves.yaml"), generator.movesYaml(header, Set.copyOf(types)), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("abilities.yaml"), generator.abilitiesYaml(header, abilityNames), StandardCharsets.UTF_8);
        System.out.println("Ruleset (generation " + generator.generation() + ") written to " + outputDir.toAbsolutePath());
    }

    /** The generation of the battle mechanics: B_UPDATED_MOVE_DATA's GEN_x. */
    private int generation() {
        return Integer.parseInt(generationOf("B_UPDATED_MOVE_DATA").substring("GEN_".length()));
    }

    /**
     * The GEN_x a setting names. The hack defines GEN_1..GEN_9 twice with different numbers (config headers and
     * pokemon.h), so settings are compared by name, never by number.
     */
    private String generationOf(String setting) {
        String value = setting;
        for (int depth = 0; !value.matches("GEN_\\d+"); depth++) {
            value = defines.get(value);
            if (value == null || depth > 20) {
                throw new IllegalStateException(setting + " names no GEN_x");
            }
        }
        return value;
    }

    // ---------------------------------------------------------------- types and type chart

    /** Type keys in the game's order (index = type id), with MYSTERY in its slot. */
    private List<String> types() throws IOException, InterruptedException {
        Map<Integer, String> byId = new TreeMap<>();
        for (Map.Entry<String, String> define : constants(read(hackSource, "include/constants/pokemon.h"), "TYPE_").entrySet()) {
            int id = Integer.parseInt(define.getValue());
            if (id < define("NUMBER_OF_MON_TYPES", "include/constants/pokemon.h")) {
                byId.put(id, define.getKey().substring("TYPE_".length()));
            }
        }
        List<String> types = List.copyOf(byId.values());
        Map<String, String> names = referenceTypeNames();
        List<String> unknown = types.stream().filter(type -> !HIDDEN_TYPES.contains(type) && !names.containsKey(type)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("New types (add them to PokemonType and theme.css, name them here): " + unknown);
        }
        return types;
    }

    private String typeChartYaml(String header, List<String> types) throws IOException, InterruptedException {
        String source = preprocess(read(typeChartSource, "src/battle_util.c"));
        int start = source.indexOf("sTypeEffectivenessTable[NUMBER_OF_MON_TYPES][NUMBER_OF_MON_TYPES] =");
        int end = source.indexOf("};", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("sTypeEffectivenessTable not found in battle_util.c");
        }
        List<List<String>> rows = new ArrayList<>();
        Matcher row = TYPE_ROW.matcher(source.substring(start, end));
        while (row.find()) {
            List<String> values = new ArrayList<>();
            Matcher multiplier = MULTIPLIER.matcher(row.group(1));
            while (multiplier.find()) {
                values.add(multiplier.group(1) != null ? multiplier.group(1) : "1.0");
            }
            rows.add(values);
        }
        if (rows.size() != types.size() || rows.stream().anyMatch(values -> values.size() != types.size())) {
            throw new IllegalStateException("The type table is not " + types.size() + "x" + types.size());
        }
        Map<String, String> names = referenceTypeNames();
        List<String> used = types.stream().filter(type -> !HIDDEN_TYPES.contains(type)).toList();

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# effectiveness: attacking type -> defending type -> multiplier. Pairs not listed are 1x.\n");
        yaml.append("# B_STEEL_RESISTANCES ").append(generationOf("B_STEEL_RESISTANCES")).append(".\n");
        yaml.append("types:\n");
        used.forEach(type -> yaml.append("  ").append(type).append(": ").append(nameMap(names.get(type))).append('\n'));
        yaml.append("effectiveness:\n");
        for (String attacker : used) {
            List<String> values = rows.get(types.indexOf(attacker));
            List<String> entries = new ArrayList<>();
            for (String defender : used) {
                String multiplier = values.get(types.indexOf(defender)).replaceFirst("\\.0$", "");
                if (!multiplier.equals("1")) {
                    entries.add(defender + ": " + multiplier);
                }
            }
            yaml.append("  ").append(attacker).append(": { ").append(String.join(", ", entries)).append(" }\n");
        }
        return yaml.toString();
    }

    // ---------------------------------------------------------------- Pokémon

    private record HackSpecies(String constant, String name, List<String> types, List<Integer> stats, List<String> abilities, String sprite) {

        String requireSprite() {
            if (sprite == null) {
                throw new IllegalStateException(constant + " has no front sprite in front_pic_table.h");
            }
            return sprite;
        }
    }

    private record ReferenceForm(int key, String name, String sprite, String region) {
    }

    private record ReferenceSpecies(String name, List<ReferenceForm> forms) {
    }

    private String pokemonYaml(String header, Set<String> types, Map<String, String> abilityNames) throws IOException, InterruptedException {
        Map<String, Integer> speciesIds = speciesIds();
        Map<String, String> hackAbilityNames = abilityNames();
        Map<String, String> pokeApiAbilities = pokeApiAbilities();
        Map<Integer, HackSpecies> base = new TreeMap<>();
        Map<String, HackSpecies> forms = new LinkedHashMap<>();
        if (speciesInfoLayout) {
            readSpeciesInfo(speciesIds, types, base, forms);
        } else {
            readBaseStats(speciesIds, types, base, forms);
        }
        Map<Integer, ReferenceSpecies> referenceSpecies = referenceSpecies();
        Map<String, String> speciesIdentifiers = pokeApiColumns("pokemon_species", "id", "identifier");
        Map<String, String> pokemonIdentifiers = pokeApiColumns("pokemon", "id", "identifier");
        Set<String> spriteNames = new TreeSet<>();

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Key: national dex number. name: as shown in the game. sprite: the hack's own sprite (graphics/pokemon/<sprite>.png).\n");
        yaml.append("# forms: keys, names and regions as in the reference ruleset; types, stats, abilities and sprites from the hack.\n");
        yaml.append("# stats: HP, Attack, Defense, Sp. Atk, Sp. Def, Speed. abilities: both slots and the hidden one.\n");
        yaml.append("pokemon:\n");
        for (Map.Entry<Integer, ReferenceSpecies> entry : referenceSpecies.entrySet()) {
            int dex = entry.getKey();
            HackSpecies species = base.get(dex);
            if (species == null) {
                throw new IllegalStateException("The hack has no species #" + dex + " " + entry.getValue().name());
            }
            String identifier = speciesIdentifiers.get(String.valueOf(dex));
            String hackIdentifier = identifierOf(species.constant());
            if (!hackIdentifier.equals(identifier) && speciesInfoLayout && hackIdentifier.startsWith(identifier + "-")) {
                System.out.println("  #" + dex + " " + identifier + " = " + species.constant()); // the first form is the species
            } else if (!hackIdentifier.equals(identifier)) {
                throw new IllegalStateException("Species #" + dex + " is " + species.constant() + " in the hack, " + identifier + " in PokeAPI");
            }
            if (!species.name().equals(entry.getValue().name())) {
                System.out.println("  name #" + dex + ": " + species.name() + " (" + entry.getValue().name() + ")");
            }
            yaml.append("  ").append(dex).append(":\n");
            appendSpecies(yaml, species, abilityNames, hackAbilityNames, pokeApiAbilities, spriteNames);
            List<String> formLines = new ArrayList<>();
            for (ReferenceForm form : entry.getValue().forms()) {
                String formIdentifier = formIdentifier(form, pokemonIdentifiers, dex);
                HackSpecies data = formData(formIdentifier, forms);
                if (data == null) {
                    System.out.println("  form " + formIdentifier + ": not in the hack, left out");
                    continue;
                }
                StringBuilder line = new StringBuilder("      ").append(form.key()).append(": { name: ").append(nameMap(form.name()))
                        .append(", types: ").append(data.types()).append(", sprite: ").append(quote(data.requireSprite()));
                if (form.region() != null) {
                    line.append(", region: ").append(form.region());
                }
                line.append(", stats: ").append(data.stats());
                List<String> abilities = abilitySlugs(data, abilityNames, hackAbilityNames, pokeApiAbilities);
                if (!abilities.isEmpty()) {
                    line.append(", abilities: [").append(String.join(", ", abilities)).append(']');
                }
                formLines.add(line.append(" }").toString());
                spriteNames.add(data.sprite());
            }
            if (!formLines.isEmpty()) {
                yaml.append("    forms:\n");
                formLines.forEach(line -> yaml.append(line).append('\n'));
            }
        }
        checkSprites(spriteNames);
        return yaml.toString();
    }

    private void appendSpecies(StringBuilder yaml, HackSpecies species, Map<String, String> abilityNames, Map<String, String> hackAbilityNames,
                               Map<String, String> pokeApiAbilities, Set<String> spriteNames) {
        yaml.append("    name: ").append(nameMap(species.name())).append('\n');
        yaml.append("    types: ").append(species.types()).append('\n');
        yaml.append("    stats: ").append(species.stats()).append('\n');
        List<String> abilities = abilitySlugs(species, abilityNames, hackAbilityNames, pokeApiAbilities);
        if (!abilities.isEmpty()) {
            yaml.append("    abilities: [").append(String.join(", ", abilities)).append("]\n");
        }
        yaml.append("    sprite: ").append(quote(species.requireSprite())).append('\n');
        spriteNames.add(species.sprite());
    }

    /** pokeemerald layout: base_stats.h, species_names.h and front_pic_table.h; forms are the ids from FORMS_START on. */
    private void readBaseStats(Map<String, Integer> speciesIds, Set<String> types, Map<Integer, HackSpecies> base,
                               Map<String, HackSpecies> forms) throws IOException, InterruptedException {
        int formsStart = speciesIds.get("FORMS_START");
        Map<String, String> names = names(preprocess(read(hackSource, "src/data/text/species_names.h")));
        Map<String, String> sprites = sprites();
        for (Map.Entry<String, String> entry : entries(preprocess(read(hackSource, "src/data/pokemon/base_stats.h"))).entrySet()) {
            String constant = entry.getKey();
            Integer id = speciesIds.get(constant);
            if (id == null || id == 0 || constant.equals("SPECIES_EGG") || BATTLE_ONLY_FORM.matcher(constant).find()) {
                continue;
            }
            HackSpecies species = hackSpecies(constant, entry.getValue(), names, sprites, types);
            if (id < formsStart) {
                base.put(id, species);
            } else {
                forms.put(constant, species);
            }
        }
    }

    /**
     * Newer expansion layout: one species_info.h entry per species and form with types, stats, abilities, name and
     * front picture. The species of a dex number is its entry with the lowest id (Gen 9 ids follow the older forms);
     * all others are forms.
     */
    private void readSpeciesInfo(Map<String, Integer> speciesIds, Set<String> types, Map<Integer, HackSpecies> base,
                                 Map<String, HackSpecies> forms) throws IOException, InterruptedException {
        Map<String, Integer> dexNumbers = enumValues(read(hackSource, "include/constants/pokedex.h"), "NATIONAL_DEX_");
        Map<String, String> pictures = new HashMap<>();
        Matcher file = PIC_FILE.matcher(read(hackSource, "src/data/graphics/pokemon.h"));
        while (file.find()) {
            pictures.put(file.group(1), file.group(2));
        }
        String source = read(hackSource, SPECIES_INFO);
        StringBuilder inlined = new StringBuilder();
        Matcher include = INCLUDE.matcher(source);
        while (include.find()) {
            include.appendReplacement(inlined, Matcher.quoteReplacement(read(hackSource, "src/data/pokemon/" + include.group(1))));
        }
        include.appendTail(inlined);

        Map<Integer, Integer> baseIds = new HashMap<>();
        for (Map.Entry<String, String> entry : entries(preprocess(inlined.toString())).entrySet()) {
            String constant = entry.getKey();
            String body = entry.getValue();
            Integer id = speciesIds.get(constant);
            Matcher dexNumber = DEX_NUMBER.matcher(body);
            if (id == null || id == 0 || !dexNumber.find() || BATTLE_ONLY_FORM.matcher(constant).find()) {
                continue;
            }
            Integer dex = dexNumbers.get(dexNumber.group(1));
            Matcher name = SPECIES_NAME.matcher(body);
            Matcher picture = SPECIES_FRONT_PIC.matcher(body);
            if (dex == null || !name.find()) {
                throw new IllegalStateException(constant + ": no dex number or name");
            }
            String sprite = null;
            if (picture.find()) {
                String symbol = "gMonFrontPic_" + (picture.group(1) != null ? picture.group(1) : picture.group(2));
                sprite = symbol.equals(NO_SPRITE_SYMBOL) ? "none" : pictures.get(symbol);
                if (sprite == null) {
                    throw new IllegalStateException("No file for " + symbol);
                }
            }
            HackSpecies species = hackSpecies(constant, body, Map.of(constant, name.group(1)), sprite == null ? Map.of() : Map.of(constant, sprite), types);
            forms.put(constant, species);
            if (!baseIds.containsKey(dex) || id < baseIds.get(dex)) {
                baseIds.put(dex, id);
                base.put(dex, species);
            }
        }
        base.values().forEach(species -> forms.remove(species.constant()));
    }

    private HackSpecies hackSpecies(String constant, String body, Map<String, String> names, Map<String, String> sprites, Set<String> types) {
        List<Integer> stats = new ArrayList<>();
        for (String stat : STATS) {
            stats.add(number(field(body, stat, constant)));
        }
        List<String> speciesTypes = new ArrayList<>();
        Matcher typePair = Pattern.compile("\\.types\\s*=\\s*\\{([^}]*)}").matcher(body);
        List<String> typeConstants = typePair.find() ? Arrays.stream(typePair.group(1).split(",")).map(this::select).toList()
                : List.of(field(body, "type1", constant), field(body, "type2", constant));
        for (String typeConstant : typeConstants) {
            String type = typeConstant.replaceFirst("^TYPE_", "");
            if (!types.contains(type) || HIDDEN_TYPES.contains(type)) {
                throw new IllegalStateException(constant + ": unknown type " + type);
            }
            if (!speciesTypes.contains(type)) {
                speciesTypes.add(type); // TYPE_FIRE twice = pure Fire
            }
        }
        List<String> abilities = new ArrayList<>();
        Matcher slots = Pattern.compile("\\.abilities\\s*=\\s*\\{([^}]*)}").matcher(body);
        Matcher hidden = Pattern.compile("\\.abilityHidden\\s*=\\s*(\\w+)").matcher(body);
        List<String> constants = new ArrayList<>(slots.find() ? Arrays.stream(slots.group(1).split(",")).map(this::select).toList() : List.of());
        if (hidden.find()) {
            constants.add(hidden.group(1));
        }
        for (String ability : constants) {
            if (!ability.isEmpty() && !ability.equals("ABILITY_NONE") && !abilities.contains(ability)) {
                abilities.add(ability);
            }
        }
        String name = names.get(constant);
        if (name == null) {
            throw new IllegalStateException(constant + " has no name in species_names.h");
        }
        // null if the species has no front picture: only an error for species and forms that are written
        return new HackSpecies(constant, name, List.copyOf(speciesTypes), List.copyOf(stats), List.copyOf(abilities), sprites.get(constant));
    }

    /**
     * Ability constants -> PokeAPI identifiers, by constant ("ABILITY_LEVITATE") or else by English name; the hack's
     * own abilities get "&lt;ruleset&gt;-&lt;constant&gt;" (no effect PokeSight knows).
     */
    private List<String> abilitySlugs(HackSpecies species, Map<String, String> abilityNames, Map<String, String> hackAbilityNames,
                                      Map<String, String> pokeApiAbilities) {
        List<String> slugs = new ArrayList<>();
        for (String constant : species.abilities()) {
            String slug = ABILITY_ALIASES.getOrDefault(constant, constant.substring("ABILITY_".length()).toLowerCase().replace('_', '-'));
            String name = hackAbilityNames.get(constant);
            if (name == null) {
                throw new IllegalStateException(species.constant() + ": ability " + constant + " has no name");
            }
            if (!pokeApiAbilities.containsKey(slug)) {
                slug = pokeApiAbilities.getOrDefault(NAME_KEY + normalize(name), rulesetId + "-" + slug);
            }
            abilityNames.putIfAbsent(slug, name);
            slugs.add(slug);
        }
        return slugs;
    }

    /** PokeAPI abilities: identifier -> identifier, and {@link #NAME_KEY} + English name -> identifier where the name is unique. */
    private Map<String, String> pokeApiAbilities() throws IOException, InterruptedException {
        Map<String, String> identifiers = pokeApiColumns("abilities", "id", "identifier");
        Map<String, String> abilities = new HashMap<>();
        identifiers.values().forEach(identifier -> abilities.put(identifier, identifier));
        Map<String, List<String>> byName = new HashMap<>();
        for (String line : csvLines("ability_names")) {
            String[] values = line.split(",", 3); // ability_id,local_language_id,name
            if (values.length == 3 && values[1].equals(ENGLISH_ID) && identifiers.containsKey(values[0])) {
                byName.computeIfAbsent(normalize(values[2]), name -> new ArrayList<>()).add(identifiers.get(values[0]));
            }
        }
        byName.forEach((name, matches) -> {
            if (matches.size() == 1) {
                abilities.put(NAME_KEY + name, matches.getFirst());
            }
        });
        return abilities;
    }

    /** PokeAPI identifier of a reference form: by its sprite id, or "&lt;species&gt;-&lt;type&gt;" for type forms ("493-fire"). */
    private static String formIdentifier(ReferenceForm form, Map<String, String> pokemonIdentifiers, int dex) {
        Matcher typeForm = Pattern.compile("(\\d+)-([a-z]+)").matcher(form.sprite());
        String identifier = typeForm.matches() ? pokemonIdentifiers.get(typeForm.group(1)) + "-" + typeForm.group(2) : pokemonIdentifiers.get(form.sprite());
        if (identifier == null) {
            throw new IllegalStateException("Unknown sprite " + form.sprite() + " of #" + dex);
        }
        return identifier;
    }

    /**
     * The hack's form for a PokeAPI form identifier: the exact match, else the shortest form constant that contains the
     * identifier's parts or is contained in them ("tauros-paldea-combat-breed" = TAUROS_PALDEA_COMBAT); null if none.
     */
    private static HackSpecies formData(String identifier, Map<String, HackSpecies> forms) {
        List<String> wanted = List.of(identifier.split("-"));
        HackSpecies best = null;
        for (HackSpecies candidate : forms.values()) {
            List<String> tokens = Arrays.stream(identifierOf(candidate.constant()).split("-"))
                    .map(token -> REGION_TOKENS.getOrDefault(token, token)).toList();
            if (tokens.equals(wanted)) {
                return candidate;
            }
            boolean related = tokens.getFirst().equals(wanted.getFirst()) && (wanted.containsAll(tokens) || tokens.containsAll(wanted));
            if (related && (best == null || tokens.size() < identifierOf(best.constant()).split("-").length)) {
                best = candidate;
            }
        }
        if (best != null) {
            System.out.println("  form " + identifier + " = " + best.constant());
        }
        return best;
    }

    /** Species constant -> its value (dex number, FORMS_START + n, ...). */
    private Map<String, Integer> speciesIds() throws IOException, InterruptedException {
        Map<String, String> raw = constants(read(hackSource, "include/constants/species.h"), "");
        Map<String, Integer> values = new HashMap<>();
        for (String constant : raw.keySet()) {
            Integer value = evaluate(constant, raw, values, 0);
            if (value != null && (constant.startsWith("SPECIES_") || constant.equals("FORMS_START"))) {
                values.put(constant, value);
            }
        }
        return values;
    }

    /** Sums such as {@code FORMS_START + 1}; null if the expression is something else. */
    private static Integer evaluate(String constant, Map<String, String> raw, Map<String, Integer> done, int depth) {
        if (done.containsKey(constant)) {
            return done.get(constant);
        }
        String expression = raw.get(constant);
        if (expression == null || depth > 20) {
            return null;
        }
        int sum = 0;
        for (String term : expression.split("\\+")) {
            String token = term.trim().replaceAll("^\\((.*)\\)$", "$1").trim();
            Integer value = token.matches("\\d+") ? Integer.valueOf(token) : evaluate(token, raw, done, depth + 1);
            if (value == null) {
                return null;
            }
            sum += value;
        }
        return sum;
    }

    /** Species constant -> sprite name: the path below graphics/pokemon of its front picture ("bulbasaur/anim_front"). */
    private Map<String, String> sprites() throws IOException, InterruptedException {
        Map<String, String> files = new HashMap<>();
        Matcher file = PIC_FILE.matcher(read(hackSource, "src/data/graphics/pokemon.h"));
        while (file.find()) {
            files.put(file.group(1), file.group(2));
        }
        Map<String, String> sprites = new HashMap<>();
        Matcher entry = FRONT_PIC.matcher(preprocess(read(hackSource, "src/data/pokemon_graphics/front_pic_table.h")));
        while (entry.find()) {
            String symbol = entry.group(2);
            String path = symbol.equals(NO_SPRITE_SYMBOL) ? "none" : files.get(symbol);
            if (path == null) {
                throw new IllegalStateException("No file for " + symbol);
            }
            sprites.put("SPECIES_" + entry.group(1), path);
        }
        return sprites;
    }

    /** Every sprite must exist (the game's spriteUrl points to the hack's graphics folder). */
    private void checkSprites(Set<String> sprites) throws InterruptedException {
        List<String> missing = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore slots = new Semaphore(16);
            for (String sprite : sprites) {
                if (sprite.equals("none")) {
                    continue;
                }
                executor.submit(() -> {
                    slots.acquire();
                    try {
                        if (!exists(hackSource, SPRITE_PATH.replace("{name}", sprite))) {
                            synchronized (missing) {
                                missing.add(sprite);
                            }
                        }
                    } finally {
                        slots.release();
                    }
                    return null;
                });
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Sprites missing in the hack: " + new TreeSet<>(missing));
        }
        System.out.println("  " + sprites.size() + " sprites found (" + SPRITE_PATH + ")");
    }

    // ---------------------------------------------------------------- moves

    private String movesYaml(String header, Set<String> types) throws IOException, InterruptedException {
        Map<Integer, String> referenceMoves = new HashMap<>();
        Map<String, Integer> referenceByName = new HashMap<>();
        for (String line : Files.readAllLines(reference.resolve("moves.yaml"), StandardCharsets.UTF_8)) {
            Matcher move = REF_MOVE.matcher(line);
            if (move.find()) {
                int id = Integer.parseInt(move.group(1));
                referenceMoves.put(id, move.group(5));
                referenceByName.put(normalize(move.group(2)), id);
            }
        }
        Map<String, String> pokeApiMoves = pokeApiColumns("moves", "identifier", "id");
        Set<String> pokeApiNames = new java.util.HashSet<>();
        for (String line : csvLines("move_names")) {
            String[] values = line.split(",", 3); // move_id,local_language_id,name
            if (values.length == 3 && values[1].equals(ENGLISH_ID)) {
                pokeApiNames.add(normalize(values[2]));
            }
        }
        Map<String, Integer> hackIds = new HashMap<>();
        constants(read(hackSource, "include/constants/moves.h"), "MOVE_").forEach((constant, value) -> {
            if (value.matches("\\d+")) {
                hackIds.put(constant, Integer.parseInt(value));
            }
        });
        Map<String, String> names = names(preprocess(read(hackSource, "src/data/text/move_names.h")));

        Map<Integer, String> moves = new TreeMap<>();
        List<String> own = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries(preprocess(read(hackSource, "src/data/battle_moves.h"))).entrySet()) {
            String constant = entry.getKey();
            String body = entry.getValue();
            if (!body.contains(".power")) {
                continue;
            }
            String split = select(field(body, "split", constant));
            int power = number(field(body, "power", constant));
            String name = names.getOrDefault(constant, "");
            String identifier = constant.substring("MOVE_".length()).toLowerCase().replace('_', '-');
            identifier = OLD_MOVE_IDENTIFIERS.getOrDefault(identifier, identifier);
            String pokeApiId = pokeApiMoves.get(identifier);
            Integer id = pokeApiId != null ? Integer.valueOf(pokeApiId) : referenceByName.get(normalize(name));
            boolean official = pokeApiId != null || pokeApiNames.contains(normalize(name));
            if (power <= MAX_PLACEHOLDER_POWER || split.equals("SPLIT_STATUS")) {
                continue;
            }
            String category = CATEGORIES.get(split);
            if (category == null) {
                category = id != null ? referenceMoves.get(id) : null; // SPLIT_HIGHEST: the reference's category
            }
            if (category == null && !official) {
                System.out.println("  " + name + ": left out (" + split + ", the category follows the user's stats)");
                continue;
            }
            if (official && id != null && !referenceMoves.containsKey(id) || !official && !hackIds.containsKey(constant)) {
                continue; // Z-Move, Max Move (ids after MOVES_COUNT) or variable power in the reference
            }
            String type = select(field(body, "type", constant)).replaceFirst("^TYPE_", "");
            if (!types.contains(type) || category == null || name.isEmpty()) {
                throw new IllegalStateException(constant + ": type " + type + ", category " + split + ", name '" + name + "'");
            }
            if (official) {
                if (id == null) {
                    throw new IllegalStateException(constant + " (" + name + ") is official but matches no move id");
                }
            } else {
                id = NEW_MOVE_KEY_BASE + hackIds.get(constant);
                own.add(name);
            }
            moves.put(id, "{ name: " + nameMap(name) + ", type: " + type + ", power: " + power + ", category: " + category + " }");
        }
        System.out.println("  the hack's own damaging moves: " + own);
        Set<Integer> missing = new TreeSet<>(referenceMoves.keySet());
        missing.removeAll(moves.keySet());
        if (!missing.isEmpty()) {
            System.out.println("  reference moves the hack lacks or has without power: " + missing);
        }

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Damaging moves with a fixed power. Key: move id (PokeAPI); ").append(NEW_MOVE_KEY_BASE)
                .append(" + the hack's move id for its own moves. name: as shown in the game.\n");
        yaml.append("# B_UPDATED_MOVE_DATA ").append(generationOf("B_UPDATED_MOVE_DATA")).append(", B_PHYSICAL_SPECIAL_SPLIT ")
                .append(generationOf("B_PHYSICAL_SPECIAL_SPLIT")).append(".\n");
        yaml.append("moves:\n");
        moves.forEach((id, move) -> yaml.append("  ").append(id).append(": ").append(move).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- abilities

    /** Ability constant -> in-game name (the name table at the top of abilities.h). */
    private Map<String, String> abilityNames() throws IOException, InterruptedException {
        String source = preprocess(read(hackSource, "src/data/text/abilities.h"));
        int start = source.indexOf("gAbilityNames");
        int end = source.indexOf("};", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("gAbilityNames not found in abilities.h");
        }
        return names(source.substring(start, end));
    }

    private String abilitiesYaml(String header, Map<String, String> names) {
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# The generation, which decides how abilities work, and the names of the abilities in pokemon.yaml.\n");
        yaml.append("# Identifiers as in PokeAPI; \"" + rulesetId + "-...\" = made up by the hack (none of the effects PokeSight knows).\n");
        yaml.append("generation: ").append(generation()).append('\n');
        yaml.append("abilities:\n");
        names.forEach((slug, name) -> yaml.append("  ").append(slug).append(": ").append(nameMap(name)).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- reference ruleset and PokeAPI

    private Map<String, String> referenceTypeNames() throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        boolean inTypes = false;
        for (String line : Files.readAllLines(reference.resolve("type-chart.yaml"), StandardCharsets.UTF_8)) {
            if (!line.startsWith(" ")) {
                inTypes = line.equals("types:");
                continue;
            }
            Matcher type = REF_TYPE.matcher(line);
            if (inTypes && type.find()) {
                names.put(type.group(1), type.group(2));
            }
        }
        return names;
    }

    private Map<Integer, ReferenceSpecies> referenceSpecies() throws IOException {
        Map<Integer, ReferenceSpecies> species = new TreeMap<>();
        Integer current = null;
        String name = null;
        List<ReferenceForm> forms = new ArrayList<>();
        for (String line : Files.readAllLines(reference.resolve("pokemon.yaml"), StandardCharsets.UTF_8)) {
            Matcher header = REF_SPECIES.matcher(line);
            Matcher speciesName = REF_SPECIES_NAME.matcher(line);
            Matcher form = REF_FORM.matcher(line);
            if (header.find()) {
                if (current != null) {
                    species.put(current, new ReferenceSpecies(name, List.copyOf(forms)));
                }
                current = Integer.parseInt(header.group(1));
                name = header.group(2);
                forms.clear();
            } else if (speciesName.find()) {
                name = speciesName.group(1);
            } else if (form.find()) {
                forms.add(new ReferenceForm(Integer.parseInt(form.group(1)), form.group(2), form.group(3), form.group(4)));
            } else if (line.startsWith("      ")) {
                throw new IllegalStateException("Unreadable form line in the reference: " + line);
            }
        }
        if (current != null) {
            species.put(current, new ReferenceSpecies(name, List.copyOf(forms)));
        }
        return species;
    }

    /** Two columns of a PokeAPI CSV file (without quoted fields) as key -> value. */
    private Map<String, String> pokeApiColumns(String file, String keyColumn, String valueColumn) throws IOException, InterruptedException {
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
        return download(POKEAPI_CSV + file + ".csv").lines().filter(line -> !line.isBlank()).toList();
    }

    // ---------------------------------------------------------------- C source parsing

    /**
     * The lines of a C file that survive the preprocessor ({@code #if A op B}, {@code #if A}, {@code #ifdef},
     * {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}), without comments; active {@code #define NAME value}
     * lines with a number or a known name as value are collected.
     */
    private String preprocess(String source) {
        StringBuilder result = new StringBuilder();
        Deque<Branch> branches = new ArrayDeque<>();
        String text = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("\\\\[ \\t]*\\R", " ");
        for (String raw : text.lines().toList()) {
            String line = raw.replaceAll("//.*", "").stripTrailing();
            String directive = line.trim();
            boolean enabled = branches.stream().allMatch(Branch::active);
            if (directive.startsWith("#ifdef ")) {
                branches.push(Branch.of(enabled && defines.containsKey(directive.substring(7).trim())));
            } else if (directive.startsWith("#ifndef ")) {
                branches.push(Branch.of(enabled && !defines.containsKey(directive.substring(8).trim())));
            } else if (directive.startsWith("#if ")) {
                branches.push(Branch.of(enabled && condition(directive.substring(4).trim())));
            } else if (directive.startsWith("#elif ")) {
                Branch branch = branches.pop();
                boolean outerEnabled = branches.stream().allMatch(Branch::active);
                boolean active = outerEnabled && !branch.taken() && condition(directive.substring(6).trim());
                branches.push(new Branch(active, branch.taken() || active));
            } else if (directive.startsWith("#else")) {
                Branch branch = branches.pop();
                boolean active = branches.stream().allMatch(Branch::active) && !branch.taken();
                branches.push(new Branch(active, true));
            } else if (directive.startsWith("#endif")) {
                branches.pop();
            } else if (directive.startsWith("#define ")) {
                Matcher define = DEFINE.matcher(directive);
                Matcher function = FUNCTION_DEFINE.matcher(directive);
                if (enabled && function.matches()) {
                    if (SPECIES_DATA.matcher(function.group(3)).find()) {
                        List<String> parameters = Arrays.stream(function.group(2).split(",")).map(String::trim).toList();
                        functionMacros.put(function.group(1), new FunctionMacro(parameters, function.group(3)));
                    }
                } else if (enabled && define.matches()) {
                    String value = define.group(2).trim();
                    if (value.matches("-?\\w+")) {
                        defines.put(define.group(1), value);
                    } else {
                        macros.put(define.group(1), value); // shared species fields, expressions such as (A ? 90 : 80)
                    }
                }
            } else if (directive.startsWith("#") && !directive.startsWith("#include") && !directive.startsWith("#undef")) {
                throw new IllegalStateException("Unsupported directive: " + directive);
            } else if (enabled) {
                result.append(expandMacros(line)).append('\n');
            }
        }
        return result.toString();
    }

    private record FunctionMacro(List<String> parameters, String body) {

        /** The body with the arguments in place of the parameters and {@code ##} pasted. */
        String expand(List<String> arguments) {
            if (arguments.size() != parameters.size()) {
                throw new IllegalStateException("Macro expects " + parameters + ", got " + arguments);
            }
            String text = body;
            for (int i = 0; i < parameters.size(); i++) {
                text = text.replaceAll("\\b" + Pattern.quote(parameters.get(i)) + "\\b", Matcher.quoteReplacement(arguments.get(i)));
            }
            return text.replaceAll("\\s*##\\s*", "");
        }
    }

    /** Replaces macros, also those inside macro bodies (GARDEVOIR_MISC_INFO uses RALTS_FAMILY_TYPE2). */
    private String expandMacros(String line) {
        String text = line;
        for (int depth = 0; depth < 10 && !(macros.isEmpty() && functionMacros.isEmpty()); depth++) {
            Matcher name = MACRO_NAME.matcher(text);
            StringBuilder expanded = new StringBuilder();
            int copied = 0;
            boolean changed = false;
            while (name.find(copied)) {
                String body = macros.get(name.group());
                FunctionMacro function = functionMacros.get(name.group());
                int open = name.end();
                if (function != null && open < text.length() && text.charAt(open) == '(') {
                    int length = topLevel(text.substring(open), ')');
                    if (length < 0) {
                        throw new IllegalStateException("Macro call over several lines: " + text);
                    }
                    int close = open + length;
                    List<String> arguments = splitArguments(text.substring(open + 1, close));
                    expanded.append(text, copied, name.start()).append(function.expand(arguments));
                    copied = close + 1;
                    changed = true;
                } else {
                    expanded.append(text, copied, name.start()).append(body != null ? body : name.group());
                    copied = name.end();
                    changed |= body != null;
                }
                if (copied >= text.length()) {
                    break;
                }
            }
            if (!changed) {
                return text;
            }
            text = expanded.append(text.substring(Math.min(copied, text.length()))).toString();
        }
        return text;
    }

    /** Macro arguments: split at commas outside parentheses and braces. */
    private static List<String> splitArguments(String text) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '(' || c == '{' ? 1 : c == ')' || c == '}' ? -1 : 0;
            if (c == ',' && depth == 0) {
                arguments.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        arguments.add(text.substring(start).trim());
        return arguments;
    }

    /** One {@code #if} level: whether its current branch is active and whether one of its branches was. */
    private record Branch(boolean active, boolean taken) {

        static Branch of(boolean active) {
            return new Branch(active, active);
        }
    }

    /** Names that are not defined count as 0, as in C. */
    private boolean condition(String expression) {
        return new Expression(expression).evaluate() != 0;
    }

    /** A field value that may be a conditional ({@code B_UPDATED_MOVE_DATA >= GEN_9 ? 95 : 70}): the chosen branch. */
    private String select(String value) {
        String text = value.trim();
        if (text.startsWith("(") && topLevel(text, ')') == text.length() - 1) {
            return select(text.substring(1, text.length() - 1));
        }
        int question = topLevel(text, '?');
        if (question < 0) {
            // a name defined as another name (RALTS_FAMILY_TYPE2 -> TYPE_FAIRY), but not as a number (TYPE_FAIRY -> 18)
            while (defines.containsKey(text) && defines.get(text).matches("[A-Za-z_]\\w*")) {
                text = defines.get(text);
            }
            return text;
        }
        int colon = topLevel(text.substring(question + 1), ':');
        if (colon < 0) {
            throw new IllegalStateException("Unsupported conditional: " + value);
        }
        boolean chosen = new Expression(expandMacros(text.substring(0, question)), true).evaluate() != 0;
        return select(chosen ? text.substring(question + 1, question + 1 + colon) : text.substring(question + 2 + colon));
    }

    /** A numeric field: a number, a conditional or a name defined as one ({@code BEEDRILL_ATTACK}). */
    private int number(String value) {
        return new Expression(expandMacros(value), true).evaluate();
    }

    private static int topLevel(String text, char wanted) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (c == wanted && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A preprocessor expression with {@code defined}, {@code !}, {@code &&}, {@code ||}, comparisons, {@code + -},
     * {@code ?:} and parentheses; names that are not defined count as 0, as in C, unless the expression is strict.
     */
    private final class Expression {

        private final List<String> tokens = new ArrayList<>();
        private final boolean strict;
        private int position;

        Expression(String text) {
            this(text, false);
        }

        Expression(String text, boolean strict) {
            this.strict = strict;
            Matcher token = Pattern.compile("\\s*(\\d+|\\w+|&&|\\|\\||==|!=|<=|>=|[<>()!+?:-])").matcher(text);
            int end = 0;
            while (token.find() && token.start() == end) {
                tokens.add(token.group(1));
                end = token.end();
            }
            if (!text.substring(end).isBlank()) {
                throw new IllegalStateException("Unsupported condition: " + text);
            }
        }

        int evaluate() {
            int result = conditional();
            if (position != tokens.size()) {
                throw new IllegalStateException("Unsupported condition: " + tokens);
            }
            return result;
        }

        private int conditional() {
            int condition = or();
            if (!accept("?")) {
                return condition;
            }
            int whenTrue = conditional();
            expect(":");
            int whenFalse = conditional();
            return condition != 0 ? whenTrue : whenFalse;
        }

        private int or() {
            int left = and();
            while (accept("||")) {
                int right = and();
                left = left != 0 || right != 0 ? 1 : 0;
            }
            return left;
        }

        private int and() {
            int left = comparison();
            while (accept("&&")) {
                int right = comparison();
                left = left != 0 && right != 0 ? 1 : 0;
            }
            return left;
        }

        private int comparison() {
            int left = sum();
            String operator = position < tokens.size() ? tokens.get(position) : "";
            if (!List.of("==", "!=", "<", "<=", ">", ">=").contains(operator)) {
                return left;
            }
            position++;
            int right = sum();
            boolean result = switch (operator) {
                case "==" -> left == right;
                case "!=" -> left != right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                case ">" -> left > right;
                default -> left >= right;
            };
            return result ? 1 : 0;
        }

        private int sum() {
            int value = unary();
            while (true) {
                if (accept("+")) {
                    value += unary();
                } else if (accept("-")) {
                    value -= unary();
                } else {
                    return value;
                }
            }
        }

        private int unary() {
            if (accept("!")) {
                return unary() == 0 ? 1 : 0;
            }
            if (accept("-")) {
                return -unary();
            }
            if (accept("(")) {
                int value = conditional();
                expect(")");
                return value;
            }
            String token = next();
            if (token.equals("defined")) {
                boolean parenthesis = accept("(");
                String name = next();
                if (parenthesis) {
                    expect(")");
                }
                return defines.containsKey(name) || macros.containsKey(name) ? 1 : 0;
            }
            if (token.matches("\\d+")) {
                return Integer.parseInt(token);
            }
            return strict || defines.containsKey(token) || token.equals("TRUE") || token.equals("FALSE") ? value(token) : 0;
        }

        private boolean accept(String token) {
            if (position < tokens.size() && tokens.get(position).equals(token)) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!accept(token)) {
                throw new IllegalStateException("Expected " + token + " in " + tokens);
            }
        }

        private String next() {
            if (position >= tokens.size()) {
                throw new IllegalStateException("Incomplete condition: " + tokens);
            }
            return tokens.get(position++);
        }
    }

    private int value(String token) {
        String value = token;
        for (int depth = 0; !value.matches("-?\\d+"); depth++) {
            if (value.equals("TRUE") || value.equals("FALSE")) {
                return value.equals("TRUE") ? 1 : 0;
            }
            value = defines.get(value);
            if (value == null || depth > 20) {
                throw new IllegalStateException("Not defined: " + token);
            }
        }
        return Integer.parseInt(value);
    }

    private int define(String name) {
        return value(name);
    }

    private int define(String name, String file) throws IOException, InterruptedException {
        if (!defines.containsKey(name)) {
            preprocess(read(hackSource, file));
        }
        return define(name);
    }

    /** {@code #define <prefix>NAME value} lines: name -> raw value (comments removed). */
    private static Map<String, String> constants(String source, String prefix) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (String raw : source.lines().toList()) {
            Matcher define = DEFINE.matcher(raw.replaceAll("//.*", "").trim());
            if (define.matches() && define.group(1).startsWith(prefix)) {
                constants.put(define.group(1), define.group(2).trim());
            }
        }
        return constants;
    }

    /** The members of {@code enum { ... }} blocks whose names start with the prefix: name -> value (0, 1, 2, ...). */
    private static Map<String, Integer> enumValues(String source, String prefix) {
        Map<String, Integer> values = new HashMap<>();
        Matcher block = Pattern.compile("(?s)enum\\s*\\{(.*?)}").matcher(source.replaceAll("//.*", ""));
        while (block.find()) {
            int next = 0;
            for (String member : block.group(1).split(",")) {
                String name = member.replaceAll("(?m)^\\s*#.*$", "").trim();
                if (name.matches("\\w+")) {
                    if (name.startsWith(prefix)) {
                        values.put(name, next);
                    }
                    next++;
                }
            }
        }
        return values;
    }

    /** Designated initializers {@code [CONSTANT] = ...} in source order: constant -> body up to the next one. */
    private static Map<String, String> entries(String source) {
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(source);
        String constant = null;
        int bodyStart = 0;
        while (matcher.find()) {
            if (constant != null) {
                entries.put(constant, source.substring(bodyStart, matcher.start()));
            }
            constant = matcher.group(1);
            bodyStart = matcher.end();
        }
        if (constant != null) {
            entries.put(constant, source.substring(bodyStart));
        }
        return entries;
    }

    /** {@code [CONSTANT] = _("Name"),} entries: constant -> name. */
    private static Map<String, String> names(String source) {
        Map<String, String> names = new HashMap<>();
        Matcher name = NAME_ENTRY.matcher(source);
        while (name.find()) {
            names.put(name.group(1), name.group(2));
        }
        return names;
    }

    private static String field(String body, String field, String constant) {
        Matcher matcher = Pattern.compile("\\." + field + "\\s*=\\s*([^,\\n]+),").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(constant + ": field ." + field + " missing");
        }
        return matcher.group(1).trim();
    }

    // ---------------------------------------------------------------- helpers

    /** {@code SPECIES_RATTATA_ALOLA} -> {@code rattata-alola}, the shape of PokeAPI identifiers. */
    private static String identifierOf(String constant) {
        return constant.substring("SPECIES_".length()).toLowerCase().replace('_', '-');
    }

    private static String normalize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
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

    private String read(String base, String path) throws IOException, InterruptedException {
        return base.startsWith("http") ? download(base + "/" + path) : Files.readString(Path.of(base, path), StandardCharsets.UTF_8);
    }

    private boolean exists(String base, String path) throws IOException, InterruptedException {
        if (!base.startsWith("http")) {
            return Files.isRegularFile(Path.of(base, path));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/" + path)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status != 200 && status != 404) {
            throw new IOException("HTTP " + status + " for " + base + "/" + path);
        }
        return status == 200;
    }

    private String download(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Download of " + url + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }
}
