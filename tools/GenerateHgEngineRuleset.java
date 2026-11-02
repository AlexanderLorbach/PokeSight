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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a ruleset for a ROM hack built with hg-engine (https://github.com/BluRosie/hg-engine) from the hack's
 * own source files.
 *
 * <p>Usage: {@code java tools/GenerateHgEngineRuleset.java <hackSource> <hgEngineSource> <referenceRuleset> <outputDir>}
 * <br>Example (Heartfelt Gold):
 * <pre>
 * java tools/GenerateHgEngineRuleset.java \
 *   https://raw.githubusercontent.com/Trinsic64/Heart-Gold-Retiled/79d28acd8b5ffe7444d5a0f6fed99a55944e0c88/HG-Engine \
 *   https://raw.githubusercontent.com/BluRosie/hg-engine/61b510cfb6cf7d77c3b083150fad3499f82fd326 \
 *   src/main/resources/rulesets/gen9 src/main/resources/rulesets/heartfelt-gold
 * </pre>
 * Sources are base URLs or local directories.
 *
 * <p>From the hack: {@code include/config.h} (generation, Fairy type, "Champions" move changes),
 * {@code data/Species.c} (in-game names, types, base stats, abilities), {@code data/HiddenAbilityTable.c} and
 * {@code data/Moves.c} (in-game names, power, type, category). From hg-engine: the type chart in
 * {@code src/battle/battle_pokemon.c} (hacks usually do not change it). The reference ruleset (generated from PokeAPI)
 * only supplies what the hack's sources lack: dex numbers are checked against PokeAPI identifiers, form keys, form
 * names, sprites and regions come from the reference, ability and type names too, and moves are keyed by the
 * reference's move ids (which also excludes variable-power moves, Z-Moves and Max Moves as the reference does).
 *
 * <p>Writes {@code type-chart.yaml}, {@code pokemon.yaml}, {@code moves.yaml} and {@code abilities.yaml}, English only
 * (hg-engine works on the US ROM).
 */
public class GenerateHgEngineRuleset {

    private static final String POKEAPI_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/";
    private static final String POKEAPI_ENGLISH = "9";
    private static final String LANGUAGE = "EN";
    /** Entries in Species.c that are no Pokémon: unused slots and the eggs. */
    private static final String PLACEHOLDER_NAME = "-----";
    private static final Set<String> NON_POKEMON = Set.of("SPECIES_NONE", "SPECIES_EGG", "SPECIES_BAD_EGG");
    private static final String NO_ABILITY = "ABILITY_NONE";
    /** hg-engine form constants use adjectives, PokeAPI identifiers the region ("RATTATA_ALOLAN" = "rattata-alola"). */
    private static final Map<String, String> REGION_TOKENS = Map.of(
            "alolan", "alola", "galarian", "galar", "hisuian", "hisui", "paldean", "paldea");
    /**
     * Species whose type-changing forms hg-engine keeps as one species (the type follows the held item in battle):
     * PokeAPI id -> nothing to look up, the form's type is the last part of its identifier ("silvally-fire").
     */
    private static final Set<Integer> TYPE_BY_ITEM_SPECIES = Set.of(773);
    private static final Map<String, String> MULTIPLIERS = Map.of(
            "TYPE_MUL_SUPER_EFFECTIVE", "2", "TYPE_MUL_NOT_EFFECTIVE", "0.5", "TYPE_MUL_NO_EFFECT", "0");
    /** Rows of the type table that are markers, not type pairs. */
    private static final Set<String> TYPE_TABLE_MARKERS = Set.of("TYPE_RING_TARGET", "TYPE_FORESIGHT", "TYPE_ENDTABLE");
    private static final Map<String, String> CATEGORIES = Map.of("SPLIT_PHYSICAL", "PHYSICAL", "SPLIT_SPECIAL", "SPECIAL");
    /** Variable powers are 0 or 1 (as in PokeAPI). */
    private static final int MAX_PLACEHOLDER_POWER = 1;

    private static final Pattern ENTRY = Pattern.compile("\\n {4}\\[(\\w+)] = \\{");
    private static final Pattern TERNARY = Pattern.compile("\\(\\((\\w+)\\) \\? \\(([^()]*)\\) : \\(([^()]*)\\)\\)");
    private static final Pattern DEFINE = Pattern.compile("#define\\s+(\\w+)(?:\\s+(\\S+))?");
    private static final Pattern CONDITION = Pattern.compile("(\\w+)\\s*(==|!=|<=|>=|<|>)\\s*(\\w+)");
    private static final Pattern TYPE_ROW = Pattern.compile("\\{\\s*(\\w+),\\s*(\\w+),\\s*(\\w+)\\s*}");
    private static final Pattern HIDDEN_ABILITY = Pattern.compile("\\[(SPECIES_\\w+)\\s*]\\s*=\\s*(ABILITY_\\w+)");
    // Reference ruleset lines (see GenerateRuleset's output)
    private static final Pattern REF_TYPE = Pattern.compile("^ {2}([A-Z]+): \\{.*EN: \"([^\"]*)\"");
    private static final Pattern REF_ABILITY = Pattern.compile("^ {2}([a-z0-9-]+): \\{.*EN: \"([^\"]*)\"");
    private static final Pattern REF_MOVE = Pattern.compile("^ {2}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\"");
    private static final Pattern REF_SPECIES = Pattern.compile("^ {2}(\\d+):(?: \\{ name: \\{.*?EN: \"([^\"]*)\")?");
    private static final Pattern REF_SPECIES_NAME = Pattern.compile("^ {4}name: \\{.*?EN: \"([^\"]*)\"");
    private static final Pattern REF_FORM = Pattern.compile(
            "^ {6}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }.*?sprite: \"([^\"]*)\"(?:, region: (\\w+))?");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String hackSource;
    private final String engineSource;
    private final Path reference;
    private final Map<String, Integer> config;

    private GenerateHgEngineRuleset(String hackSource, String engineSource, Path reference) throws IOException, InterruptedException {
        this.hackSource = hackSource;
        this.engineSource = engineSource;
        this.reference = reference;
        this.config = parseConfig(read(hackSource, "include/config.h"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: java tools/GenerateHgEngineRuleset.java <hackSource> <hgEngineSource> <referenceRuleset> <outputDir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[3]);
        Files.createDirectories(outputDir);
        GenerateHgEngineRuleset generator = new GenerateHgEngineRuleset(args[0], args[1], Path.of(args[2]));
        String header = "# Generated by tools/GenerateHgEngineRuleset.java from the hg-engine sources of the hack (" + args[0]
                + ") and hg-engine's type chart (" + args[1] + ") - do not edit by hand.\n";

        Map<String, String> typeNames = generator.referenceTypeNames();
        Set<String> usedAbilities = new TreeSet<>();
        Files.writeString(outputDir.resolve("type-chart.yaml"), generator.typeChartYaml(header, typeNames), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("pokemon.yaml"), generator.pokemonYaml(header, typeNames.keySet(), usedAbilities), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("moves.yaml"), generator.movesYaml(header, typeNames.keySet()), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("abilities.yaml"), generator.abilitiesYaml(header, usedAbilities), StandardCharsets.UTF_8);
        System.out.println("Ruleset (generation " + generator.generation() + ") written to " + outputDir.toAbsolutePath());
    }

    private int generation() {
        return config("GEN_LATEST");
    }

    private int config(String name) {
        Integer value = config.get(name);
        if (value == null) {
            throw new IllegalStateException("config.h does not define " + name);
        }
        return value;
    }

    // ---------------------------------------------------------------- type chart

    private String typeChartYaml(String header, Map<String, String> typeNames) throws IOException, InterruptedException {
        String source = read(engineSource, "src/battle/battle_pokemon.c");
        int start = source.indexOf("TypeEffectivenessTable[][3] = {");
        int end = source.indexOf("\n};", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("TypeEffectivenessTable not found in battle_pokemon.c");
        }
        Map<String, Map<String, String>> chart = new LinkedHashMap<>();
        for (String line : preprocess(source.substring(start, end))) {
            Matcher row = TYPE_ROW.matcher(line);
            if (!row.find() || TYPE_TABLE_MARKERS.contains(row.group(1))) {
                continue;
            }
            String attacker = typeKey(row.group(1), typeNames.keySet());
            String defender = typeKey(row.group(2), typeNames.keySet());
            String multiplier = MULTIPLIERS.get(row.group(3));
            if (multiplier == null) {
                throw new IllegalStateException("Unknown multiplier " + row.group(3));
            }
            chart.computeIfAbsent(attacker, k -> new HashMap<>()).put(defender, multiplier);
        }
        boolean fairy = config("FAIRY_TYPE_IMPLEMENTED") == 1;
        List<String> types = typeNames.keySet().stream().filter(type -> fairy || !type.equals("FAIRY")).toList();

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# effectiveness: attacking type -> defending type -> multiplier. Pairs not listed are 1x.\n");
        yaml.append("# FAIRY_TYPE_IMPLEMENTED ").append(config("FAIRY_TYPE_IMPLEMENTED"))
                .append(", TYPE_EFFECTIVENESS_GEN ").append(config("TYPE_EFFECTIVENESS_GEN")).append(".\n");
        yaml.append("types:\n");
        types.forEach(type -> yaml.append("  ").append(type).append(": ").append(nameMap(typeNames.get(type))).append('\n'));
        yaml.append("effectiveness:\n");
        for (String attacker : types) {
            Map<String, String> row = chart.getOrDefault(attacker, Map.of());
            List<String> entries = types.stream().filter(row::containsKey).map(defender -> defender + ": " + row.get(defender)).toList();
            yaml.append("  ").append(attacker).append(": { ").append(String.join(", ", entries)).append(" }\n");
        }
        return yaml.toString();
    }

    // ---------------------------------------------------------------- Pokémon

    private record HackSpecies(String constant, String name, List<String> types, List<Integer> stats, List<String> abilities) {
    }

    private record ReferenceForm(int key, String name, String sprite, String region) {
    }

    private record ReferenceSpecies(String name, List<ReferenceForm> forms) {
    }

    private String pokemonYaml(String header, Set<String> types, Set<String> usedAbilities) throws IOException, InterruptedException {
        Map<String, String> hiddenAbilities = new HashMap<>();
        Matcher hidden = HIDDEN_ABILITY.matcher(read(hackSource, "data/HiddenAbilityTable.c"));
        while (hidden.find()) {
            hiddenAbilities.put(hidden.group(1), hidden.group(2));
        }
        Map<String, HackSpecies> byConstant = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries(read(hackSource, "data/Species.c")).entrySet()) {
            byConstant.put(entry.getKey(), hackSpecies(entry.getKey(), entry.getValue(), hiddenAbilities.get(entry.getKey()), types));
        }
        // Base species come first, in national dex order (the dex check below verifies it); then the forms, named like
        // their base species (regional forms) or with the placeholder name.
        List<HackSpecies> baseSpecies = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (HackSpecies species : byConstant.values()) {
            if (NON_POKEMON.contains(species.constant()) || species.name().equals(PLACEHOLDER_NAME)) {
                continue;
            }
            if (!seenNames.add(species.name())) {
                break;
            }
            baseSpecies.add(species);
        }
        Set<HackSpecies> baseSet = Set.copyOf(baseSpecies);
        Map<Integer, ReferenceSpecies> referenceSpecies = referenceSpecies();
        Map<String, String> identifiers = pokeApiIdentifiers();

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Key: national dex number. name: as shown in the game (hg-engine cuts names to 10 letters).\n");
        yaml.append("# forms: keys, names, sprites and regions as in the reference ruleset; types, stats and abilities from the hack.\n");
        yaml.append("# stats: HP, Attack, Defense, Sp. Atk, Sp. Def, Speed. abilities: both slots and the hidden one.\n");
        yaml.append("pokemon:\n");
        for (Map.Entry<Integer, ReferenceSpecies> entry : referenceSpecies.entrySet()) {
            int dex = entry.getKey();
            if (dex > baseSpecies.size()) {
                throw new IllegalStateException("The hack has no species #" + dex + " " + entry.getValue().name());
            }
            HackSpecies species = baseSpecies.get(dex - 1);
            String identifier = identifiers.get(String.valueOf(dex));
            if (!identifier.startsWith(identifierOf(species.constant()))) {
                throw new IllegalStateException("Species #" + dex + " is " + species.constant() + " in the hack, " + identifier + " in PokeAPI");
            }
            if (!species.name().equals(entry.getValue().name())) {
                System.out.println("  name #" + dex + ": " + species.name() + " (" + entry.getValue().name() + ")");
            }
            usedAbilities.addAll(species.abilities());
            yaml.append("  ").append(dex).append(":\n");
            yaml.append("    name: ").append(nameMap(species.name())).append('\n');
            yaml.append("    types: ").append(species.types()).append('\n');
            yaml.append("    stats: ").append(species.stats()).append('\n');
            if (!species.abilities().isEmpty()) {
                yaml.append("    abilities: [").append(String.join(", ", species.abilities())).append("]\n");
            }
            if (!entry.getValue().forms().isEmpty()) {
                yaml.append("    forms:\n");
                for (ReferenceForm form : entry.getValue().forms()) {
                    HackSpecies data = formData(dex, species, form, identifiers, byConstant, baseSet);
                    usedAbilities.addAll(data.abilities());
                    yaml.append("      ").append(form.key()).append(": { name: ").append(nameMap(form.name()))
                            .append(", types: ").append(data.types())
                            .append(", sprite: ").append(quote(form.sprite()));
                    if (form.region() != null) {
                        yaml.append(", region: ").append(form.region());
                    }
                    yaml.append(", stats: ").append(data.stats());
                    if (!data.abilities().isEmpty()) {
                        yaml.append(", abilities: [").append(String.join(", ", data.abilities())).append(']');
                    }
                    yaml.append(" }\n");
                }
            }
        }
        return yaml.toString();
    }

    private HackSpecies hackSpecies(String constant, String body, String hiddenAbility, Set<String> types) {
        String name = field(body, "name");
        Matcher typeMatch = Pattern.compile("\\.types = \\{ (\\w+), (\\w+) }").matcher(body);
        Matcher abilityMatch = Pattern.compile("\\.abilities = \\{ (\\w+), (\\w+) }").matcher(body);
        if (!typeMatch.find() || !abilityMatch.find()) {
            throw new IllegalStateException(constant + ": types or abilities missing");
        }
        List<String> speciesTypes = new ArrayList<>();
        for (String type : List.of(resolve(typeMatch.group(1)), resolve(typeMatch.group(2)))) {
            String key = typeKey(type, types);
            if (!speciesTypes.contains(key)) {
                speciesTypes.add(key); // { TYPE_FIRE, TYPE_FIRE } = pure Fire
            }
        }
        List<Integer> stats = new ArrayList<>();
        for (String stat : List.of("hp", "attack", "defense", "spAttack", "spDefense", "speed")) {
            stats.add(Integer.parseInt(field(body, stat)));
        }
        List<String> abilities = new ArrayList<>();
        for (String ability : Arrays.asList(resolve(abilityMatch.group(1)), resolve(abilityMatch.group(2)), hiddenAbility)) {
            if (ability != null && !ability.equals(NO_ABILITY)) {
                String slug = ability.substring("ABILITY_".length()).toLowerCase().replace('_', '-');
                if (!abilities.contains(slug)) {
                    abilities.add(slug);
                }
            }
        }
        return new HackSpecies(constant, name, List.copyOf(speciesTypes), List.copyOf(stats), List.copyOf(abilities));
    }

    /** The hack's data of a reference form, matched by the PokeAPI identifier of the form's sprite. */
    private HackSpecies formData(int dex, HackSpecies base, ReferenceForm form, Map<String, String> identifiers,
                                 Map<String, HackSpecies> byConstant, Set<HackSpecies> baseSpecies) {
        Matcher typeForm = Pattern.compile("(\\d+)-([a-z]+)").matcher(form.sprite());
        String identifier = typeForm.matches() ? identifiers.get(typeForm.group(1)) + "-" + typeForm.group(2) : identifiers.get(form.sprite());
        if (identifier == null) {
            throw new IllegalStateException("Unknown sprite " + form.sprite() + " of #" + dex);
        }
        if (TYPE_BY_ITEM_SPECIES.contains(dex)) {
            String type = typeForm.group(2).toUpperCase();
            return new HackSpecies(base.constant(), base.name(), List.of(type), base.stats(), base.abilities());
        }
        // Exact match, else the shortest form constant that contains the identifier's parts or is contained in them
        // ("necrozma-dusk" = NECROZMA_DUSK_MANE, not NECROZMA_ULTRA_DUSK_MANE; "tauros-paldea-combat-breed" = TAUROS_COMBAT).
        List<String> wanted = List.of(identifier.split("-"));
        HackSpecies best = null;
        for (HackSpecies candidate : byConstant.values()) {
            if (baseSpecies.contains(candidate)) {
                continue; // MR_MIME is no form of mr-mime-galar
            }
            List<String> tokens = List.of(identifierOf(candidate.constant()).split("-"));
            if (tokens.equals(wanted)) {
                best = candidate;
                break;
            }
            boolean related = tokens.getFirst().equals(wanted.getFirst()) && (wanted.containsAll(tokens) || tokens.containsAll(wanted));
            if (related && (best == null || tokens.size() < identifierOf(best.constant()).split("-").length)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("The hack has no form " + identifier + " (#" + dex + " form " + form.key() + ")");
        }
        System.out.println("  form " + identifier + " = " + best.constant());
        return best;
    }

    // ---------------------------------------------------------------- moves

    private String movesYaml(String header, Set<String> types) throws IOException, InterruptedException {
        Map<String, Integer> referenceIds = new HashMap<>();
        for (String line : Files.readAllLines(reference.resolve("moves.yaml"), StandardCharsets.UTF_8)) {
            Matcher move = REF_MOVE.matcher(line);
            if (move.find()) {
                referenceIds.put(normalize(move.group(2)), Integer.parseInt(move.group(1)));
            }
        }
        Map<Integer, String> moves = new TreeMap<>();
        List<String> unmatched = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries(read(hackSource, "data/Moves.c")).entrySet()) {
            String body = entry.getValue();
            String category = CATEGORIES.get(resolve(field(body, "split")));
            int power = Integer.parseInt(resolve(field(body, "power")));
            if (category == null || power <= MAX_PLACEHOLDER_POWER) {
                continue; // status move or variable power
            }
            Integer id = referenceIds.get(normalize(field(body, "fullName")));
            if (id == null) {
                unmatched.add(entry.getKey());
                continue;
            }
            String type = typeKey(resolve(field(body, "type")), types);
            moves.put(id, "{ name: " + nameMap(field(body, "name")) + ", type: " + type + ", power: " + power + ", category: " + category + " }");
        }
        System.out.println("  damaging moves not in the reference (Z-Moves, Max Moves, variable power): " + unmatched);
        Set<Integer> missing = new TreeSet<>(referenceIds.values());
        missing.removeAll(moves.keySet());
        if (!missing.isEmpty()) {
            System.out.println("  reference moves the hack lacks or has without power: " + missing);
        }

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Damaging moves with a fixed power. Key: move id (PokeAPI). name: as shown in the game.\n");
        yaml.append("# CHAMPIONS_POWER_CHANGES ").append(config("CHAMPIONS_POWER_CHANGES"))
                .append(", CHAMPIONS_TYPE_CHANGES ").append(config("CHAMPIONS_TYPE_CHANGES")).append(".\n");
        yaml.append("moves:\n");
        moves.forEach((id, move) -> yaml.append("  ").append(id).append(": ").append(move).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- abilities

    private String abilitiesYaml(String header, Set<String> usedAbilities) throws IOException, InterruptedException {
        Map<String, String> names = new HashMap<>();
        for (String line : Files.readAllLines(reference.resolve("abilities.yaml"), StandardCharsets.UTF_8)) {
            Matcher ability = REF_ABILITY.matcher(line);
            if (ability.find()) {
                names.put(ability.group(1), ability.group(2));
            }
        }
        Set<String> unnamed = new TreeSet<>(usedAbilities);
        unnamed.removeAll(names.keySet());
        if (!unnamed.isEmpty()) {
            // Abilities the reference's generation no longer gives anybody (Greninja's Battle Bond in Gen 9)
            System.out.println("  abilities named from PokeAPI: " + unnamed);
            names.putAll(pokeApiAbilityNames(unnamed));
        }
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# The generation, which decides how abilities work, and the names of the abilities in pokemon.yaml.\n");
        yaml.append("generation: ").append(generation()).append('\n');
        yaml.append("abilities:\n");
        usedAbilities.forEach(ability -> yaml.append("  ").append(ability).append(": ").append(nameMap(names.get(ability))).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- reference ruleset and PokeAPI

    /** Type key -> English name, in the reference's order. */
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

    /** PokeAPI pokemon id -> identifier ("10091" -> "rattata-alola"). */
    private Map<String, String> pokeApiIdentifiers() throws IOException, InterruptedException {
        return pokeApiColumns("pokemon", "id", "identifier");
    }

    /** English names of the given abilities (PokeAPI identifiers); fails if one is unknown. */
    private Map<String, String> pokeApiAbilityNames(Set<String> abilities) throws IOException, InterruptedException {
        Map<String, String> ids = pokeApiColumns("abilities", "identifier", "id");
        Map<String, String> englishNames = new HashMap<>();
        for (String line : csvLines("ability_names")) {
            String[] values = line.split(",", 3); // ability_id,local_language_id,name
            if (values.length == 3 && values[1].equals(POKEAPI_ENGLISH)) {
                englishNames.put(values[0], values[2]);
            }
        }
        Map<String, String> names = new HashMap<>();
        for (String ability : abilities) {
            String name = englishNames.get(ids.get(ability));
            if (name == null) {
                throw new IllegalStateException("Ability " + ability + " is unknown to PokeAPI");
            }
            names.put(ability, name);
        }
        return names;
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

    /** Designated initializers {@code [CONSTANT] = { ... }} in source order: constant -> body. */
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

    /** The value of {@code .field = value,} (string fields without quotes). */
    private static String field(String body, String field) {
        Matcher matcher = Pattern.compile("\\." + field + " = (\"([^\"]*)\"|[^,\\n]+),").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Field ." + field + " missing in " + body.lines().limit(3).toList());
        }
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(1).trim();
    }

    /** Resolves {@code ((CONFIG) ? (a) : (b))} with the hack's config.h. */
    private String resolve(String value) {
        Matcher ternary = TERNARY.matcher(value);
        if (!ternary.matches()) {
            return value;
        }
        return config(ternary.group(1)) != 0 ? ternary.group(2).trim() : ternary.group(3).trim();
    }

    /** {@code #define NAME value} with integer or previously defined values, honouring #ifdef / #ifndef / #else. */
    private static Map<String, Integer> parseConfig(String source) {
        Map<String, Integer> defines = new HashMap<>();
        Deque<Boolean> active = new ArrayDeque<>();
        for (String raw : source.lines().toList()) {
            String line = raw.replaceAll("//.*", "").trim();
            boolean enabled = !active.contains(false);
            if (line.startsWith("#ifdef ") || line.startsWith("#ifndef ")) {
                boolean defined = defines.containsKey(line.split("\\s+")[1]);
                active.push(line.startsWith("#ifdef ") == defined);
            } else if (line.startsWith("#if ")) {
                active.push(true); // include guards and the like; config.h has no other #if
            } else if (line.startsWith("#else")) {
                active.push(!active.pop());
            } else if (line.startsWith("#endif")) {
                active.pop();
            } else if (enabled) {
                Matcher define = DEFINE.matcher(line);
                if (define.matches()) {
                    String value = define.group(2);
                    if (value == null) {
                        defines.put(define.group(1), 1);
                    } else if (value.matches("-?\\d+")) {
                        defines.put(define.group(1), Integer.parseInt(value));
                    } else if (defines.containsKey(value)) {
                        defines.put(define.group(1), defines.get(value));
                    }
                }
            }
        }
        return defines;
    }

    /** The lines of a C snippet that survive {@code #if NAME op value} / {@code #else} / {@code #endif}. */
    private List<String> preprocess(String source) {
        List<String> lines = new ArrayList<>();
        Deque<Boolean> active = new ArrayDeque<>();
        for (String line : source.lines().map(String::trim).toList()) {
            if (line.startsWith("#if ")) {
                Matcher condition = CONDITION.matcher(line.substring(4));
                if (!condition.matches()) {
                    throw new IllegalStateException("Unsupported condition: " + line);
                }
                active.push(compare(value(condition.group(1)), condition.group(2), value(condition.group(3))));
            } else if (line.startsWith("#else")) {
                active.push(!active.pop());
            } else if (line.startsWith("#endif")) {
                active.pop();
            } else if (line.startsWith("#")) {
                throw new IllegalStateException("Unsupported directive: " + line);
            } else if (!active.contains(false)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private int value(String token) {
        return token.matches("-?\\d+") ? Integer.parseInt(token) : config(token);
    }

    private static boolean compare(int left, String operator, int right) {
        return switch (operator) {
            case "==" -> left == right;
            case "!=" -> left != right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            case ">" -> left > right;
            case ">=" -> left >= right;
            default -> throw new IllegalStateException("Unsupported operator " + operator);
        };
    }

    // ---------------------------------------------------------------- helpers

    /** {@code SPECIES_RATTATA_ALOLAN} -> {@code rattata-alola}, the shape of PokeAPI identifiers. */
    private static String identifierOf(String constant) {
        return Arrays.stream(constant.substring("SPECIES_".length()).toLowerCase().split("_"))
                .map(token -> REGION_TOKENS.getOrDefault(token, token))
                .reduce((a, b) -> a + "-" + b)
                .orElseThrow();
    }

    private static String typeKey(String constant, Set<String> types) {
        String key = constant.replaceFirst("^TYPE_", "");
        if (!types.contains(key)) {
            throw new IllegalStateException("Unknown type " + constant);
        }
        return key;
    }

    /** Move names compared without case, spaces and punctuation ("U-turn" = "U-Turn"). */
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

    /** A file below a base URL or directory. */
    private String read(String base, String path) throws IOException, InterruptedException {
        return base.startsWith("http") ? download(base + "/" + path) : Files.readString(Path.of(base, path), StandardCharsets.UTF_8);
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
