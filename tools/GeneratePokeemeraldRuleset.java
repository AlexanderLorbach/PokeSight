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
 * Sources are base URLs or local directories.
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
    /** The type no Pokémon has (Curse in Gen 2-4). */
    private static final String MYSTERY_TYPE = "MYSTERY";
    /** Move constants with an English name from before Gen 6 that PokeAPI no longer knows. */
    private static final Map<String, String> OLD_MOVE_IDENTIFIERS = Map.of("faint-attack", "feint-attack");
    private static final Map<String, String> CATEGORIES = Map.of("SPLIT_PHYSICAL", "PHYSICAL", "SPLIT_SPECIAL", "SPECIAL");
    /** Battle-only forms the reference leaves out as well. */
    private static final Pattern BATTLE_ONLY_FORM = Pattern.compile("_(MEGA|GMAX|PRIMAL)(_|$)");
    private static final String NO_SPRITE_SYMBOL = "gMonFrontPic_CircledQuestionMark";
    private static final String SPRITE_PATH = "graphics/pokemon/{name}.png";
    private static final List<String> STATS = List.of("baseHP", "baseAttack", "baseDefense", "baseSpAttack", "baseSpDefense", "baseSpeed");

    private static final Pattern DEFINE = Pattern.compile("#define\\s+(\\w+)\\s+([^/\\n]+?)\\s*(?://.*)?$");
    private static final Pattern CONDITION = Pattern.compile("(\\w+)\\s*(==|!=|<=|>=|<|>)\\s*(\\w+)");
    private static final Pattern ENTRY = Pattern.compile("\\n\\s*\\[(\\w+)]\\s*=\\s*");
    private static final Pattern NAME_ENTRY = Pattern.compile("\\[(\\w+)]\\s*=\\s*_\\(\"([^\"]*)\"\\)");
    private static final Pattern TYPE_ROW = Pattern.compile("\\{((?:\\s*X\\([0-9.]+\\),?)+)\\s*}");
    private static final Pattern MULTIPLIER = Pattern.compile("X\\(([0-9.]+)\\)");
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

    private GeneratePokeemeraldRuleset(String hackSource, String typeChartSource, Path reference, String rulesetId)
            throws IOException, InterruptedException {
        this.hackSource = hackSource;
        this.typeChartSource = typeChartSource;
        this.reference = reference;
        this.rulesetId = rulesetId;
        for (String file : CONFIG_FILES) {
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
        List<String> unknown = types.stream().filter(type -> !type.equals(MYSTERY_TYPE) && !names.containsKey(type)).toList();
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
                values.add(multiplier.group(1));
            }
            rows.add(values);
        }
        if (rows.size() != types.size() || rows.stream().anyMatch(values -> values.size() != types.size())) {
            throw new IllegalStateException("The type table is not " + types.size() + "x" + types.size());
        }
        Map<String, String> names = referenceTypeNames();
        List<String> used = types.stream().filter(type -> !type.equals(MYSTERY_TYPE)).toList();

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
        int formsStart = speciesIds.get("FORMS_START");
        Map<String, String> names = names(preprocess(read(hackSource, "src/data/text/species_names.h")));
        Map<String, String> sprites = sprites();
        Map<String, String> hackAbilityNames = abilityNames();
        Map<String, String> pokeApiAbilities = pokeApiAbilities();

        Map<Integer, HackSpecies> base = new TreeMap<>();
        Map<String, HackSpecies> forms = new LinkedHashMap<>();
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
            if (!identifier.equals(identifierOf(species.constant()))) {
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

    private HackSpecies hackSpecies(String constant, String body, Map<String, String> names, Map<String, String> sprites, Set<String> types) {
        List<Integer> stats = new ArrayList<>();
        for (String stat : STATS) {
            stats.add(Integer.parseInt(field(body, stat, constant)));
        }
        List<String> speciesTypes = new ArrayList<>();
        for (String field : List.of("type1", "type2")) {
            String type = field(body, field, constant).replaceFirst("^TYPE_", "");
            if (!types.contains(type) || type.equals(MYSTERY_TYPE)) {
                throw new IllegalStateException(constant + ": unknown type " + type);
            }
            if (!speciesTypes.contains(type)) {
                speciesTypes.add(type); // TYPE_FIRE twice = pure Fire
            }
        }
        List<String> abilities = new ArrayList<>();
        Matcher slots = Pattern.compile("\\.abilities\\s*=\\s*\\{([^}]*)}").matcher(body);
        Matcher hidden = Pattern.compile("\\.abilityHidden\\s*=\\s*(\\w+)").matcher(body);
        List<String> constants = new ArrayList<>(slots.find() ? Arrays.stream(slots.group(1).split(",")).map(String::trim).toList() : List.of());
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
            String slug = constant.substring("ABILITY_".length()).toLowerCase().replace('_', '-');
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
            List<String> tokens = List.of(identifierOf(candidate.constant()).split("-"));
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
            String split = field(body, "split", constant);
            int power = Integer.parseInt(field(body, "power", constant));
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
            String type = field(body, "type", constant).replaceFirst("^TYPE_", "");
            if (!types.contains(type) || category == null || name.isEmpty()) {
                throw new IllegalStateException(constant + ": type " + type + ", category " + split + ", name '" + name + "'");
            }
            if (official) {
                if (id == null) {
                    throw new IllegalStateException(constant + " (" + name + ") is official but matches no move id");
                }
                if (!referenceMoves.containsKey(id)) {
                    continue; // Z-Move, Max Move or variable power in the reference
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
        String text = source.replaceAll("(?s)/\\*.*?\\*/", "");
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
                if (enabled && define.matches()) {
                    String value = define.group(2).trim();
                    if (value.matches("-?\\w+")) {
                        defines.put(define.group(1), value);
                    }
                }
            } else if (directive.startsWith("#") && !directive.startsWith("#include") && !directive.startsWith("#undef")) {
                throw new IllegalStateException("Unsupported directive: " + directive);
            } else if (enabled) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    /** One {@code #if} level: whether its current branch is active and whether one of its branches was. */
    private record Branch(boolean active, boolean taken) {

        static Branch of(boolean active) {
            return new Branch(active, active);
        }
    }

    private boolean condition(String expression) {
        Matcher comparison = CONDITION.matcher(expression);
        if (comparison.matches()) {
            int left = value(comparison.group(1));
            int right = value(comparison.group(3));
            return switch (comparison.group(2)) {
                case "==" -> left == right;
                case "!=" -> left != right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                case ">" -> left > right;
                case ">=" -> left >= right;
                default -> throw new IllegalStateException(expression);
            };
        }
        if (expression.matches("\\w+")) {
            return value(expression) != 0;
        }
        throw new IllegalStateException("Unsupported condition: " + expression);
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
