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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 * Generates a ruleset for a FireRed ROM hack built with the Complete FireRed Upgrade (CFRU: battle engine, moves,
 * type chart) and the Dynamic Pokemon Expansion (DPE: species data, names, sprites) from their sources.
 *
 * <p>Usage: {@code java tools/GenerateCfruRuleset.java <dpeSource> <cfruSource> <referenceRuleset> <outputDir> [DEFINE...]}
 * <br>Example (Pokémon Unbound v2.1.1.1: DPE's Unbound branch and the CFRU commit of the same release day, built with
 * {@code UNBOUND} defined):
 * <pre>
 * java tools/GenerateCfruRuleset.java \
 *   https://raw.githubusercontent.com/Skeli789/Dynamic-Pokemon-Expansion/fe058e0e3ac23cf968cf950de43332135bc1549d \
 *   https://raw.githubusercontent.com/Skeli789/Complete-Fire-Red-Upgrade/f61fed27d7a8410425106f3be92cc6b0048560e7 \
 *   src/main/resources/rulesets/gen9 src/main/resources/rulesets/unbound UNBOUND
 * </pre>
 * Sources are base URLs or local directories; extra arguments are defined like {@code -D} flags (the hack's own build).
 *
 * <p>From DPE: {@code include/species.h}, {@code include/pokedex.h} and {@code src/Species_To_Pokdex_Table.c} (species
 * and their dex numbers), {@code src/Base_Stats.c} (types, base stats, abilities), {@code strings/Pokemon_Name_Table.string}
 * (in-game names) and {@code src/Front_Pic_Table.c} ({@code graphics/frontspr/*.png}, checked to exist). From CFRU:
 * {@code src/config.h} (its {@code #define}s), {@code include/constants/pokemon.h} (types), {@code src/Tables/type_tables.h},
 * {@code src/Tables/battle_moves.c} with {@code strings/attack_name_table.string} and the ability names. Names in the
 * string tables are in id order. The reference ruleset (generated from PokeAPI) supplies form keys, names and regions,
 * type names and move ids; species and moves are matched to PokeAPI identifiers. Moves in PokeAPI but not in the
 * reference (variable power) are left out, as are the Z-Moves and Max Moves after {@code NON_Z_MOVE_COUNT}; the hack's
 * own moves ({@code OWN_MOVES}) get key 20000 + the hack's move id, any other move PokeAPI does not know stops the
 * generator.
 *
 * <p>Writes {@code type-chart.yaml}, {@code pokemon.yaml}, {@code moves.yaml} and {@code abilities.yaml}, English only.
 */
public class GenerateCfruRuleset {

    private static final String POKEAPI_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/";
    private static final String LANGUAGE = "EN";
    /** PokeAPI's local_language_id of English. */
    private static final String ENGLISH_ID = "9";
    private static final String NAME_KEY = "name:";
    /** CFRU implements the Gen 8 battle mechanics. */
    private static final int GENERATION = 8;
    /** Variable powers are 0 or 1 (as in PokeAPI). */
    private static final int MAX_PLACEHOLDER_POWER = 1;
    /** Internal types: no Pokémon has them (MYSTERY = Curse's old "???", ROOSTLESS and BLANK are battle helpers). */
    private static final Set<String> INTERNAL_TYPES = Set.of("MYSTERY", "ROOSTLESS", "BLANK");
    private static final Map<String, String> MULTIPLIERS = Map.of(
            "TYPE_MUL_SUPER_EFFECTIVE", "2", "TYPE_MUL_NOT_EFFECTIVE", "0.5", "TYPE_MUL_NO_EFFECT", "0", "TYPE_MUL_NORMAL", "1");
    private static final Map<String, String> CATEGORIES = Map.of("SPLIT_PHYSICAL", "PHYSICAL", "SPLIT_SPECIAL", "SPECIAL");
    /** Battle-only forms the reference leaves out as well. */
    private static final Pattern BATTLE_ONLY_FORM = Pattern.compile("_(MEGA|MEGA_X|MEGA_Y|GIGA|PRIMAL)$");
    /** DPE's short form suffixes -> PokeAPI's identifier parts. */
    private static final Map<String, String> FORM_TOKENS = Map.of("a", "alola", "g", "galar", "h", "hisui", "fight", "fighting");
    /** DPE form constants whose name does not follow PokeAPI's identifier. */
    private static final Map<String, String> FORM_ALIASES = Map.of(
            "SPECIES_DARMANITANZEN", "darmanitan-zen", "SPECIES_ORICORIO_Y", "oricorio-pom-pom", "SPECIES_ORICORIO_P", "oricorio-pau",
            "SPECIES_ORICORIO_S", "oricorio-sensu", "SPECIES_LYCANROC_N", "lycanroc-midnight", "SPECIES_BASCULIN_H", "basculin-white-striped");
    /** Character codes of the string tables (charmap.tbl). */
    private static final Map<String, String> CHARACTERS = Map.of("[B5]", "♂", "[B6]", "♀", "\\e", "é");
    // The hack's own damaging moves (not in PokeAPI); key = NEW_MOVE_KEY_BASE + the hack's move id
    // Both As One abilities are named "As One" in the game; PokeAPI tells them apart by the horse
    private static final Map<String, String> ABILITY_ALIASES = Map.of(
            "ABILITY_ASONE_CHILLING", "as-one-glastrier", "ABILITY_ASONE_GRIM", "as-one-spectrier");
    private static final Set<String> OWN_MOVES = Set.of("MOVE_LEECHFANG", "MOVE_STEELYHIT");
    private static final int NEW_MOVE_KEY_BASE = 20000;
    private static final Pattern NON_Z_MOVE_COUNT = Pattern.compile("#define\\s+NON_Z_MOVE_COUNT\\s+\\((MOVE_\\w+)\\s*\\+\\s*1\\)");
    private static final List<String> SPRITE_FOLDERS = List.of("frontspr", "castform");
    private static final String SPRITE_PATH = "graphics/{name}.png";
    private static final List<String> STATS = List.of("baseHP", "baseAttack", "baseDefense", "baseSpAttack", "baseSpDefense", "baseSpeed");

    private static final Pattern DEFINE = Pattern.compile("#define\\s+(\\w+)(?:\\s+([^\\s/][^/\\n]*?))?\\s*$");
    private static final Pattern CONDITION = Pattern.compile("(\\w+)\\s*(==|!=|<=|>=|<|>)\\s*(\\w+)");
    private static final Pattern ENTRY = Pattern.compile("\\n\\s*\\[(\\w+)]\\s*=\\s*");
    private static final Pattern STRING_ENTRY = Pattern.compile("#org @NAME_\\w+\\r?\\n([^\\r\\n]*)");
    private static final Pattern TYPE_BLOCK = Pattern.compile("\\[TYPE_(\\w+)]\\s*=\\s*\\{([^}]*)}");
    private static final Pattern TYPE_PAIR = Pattern.compile("\\[TYPE_(\\w+)]\\s*=\\s*(\\w+)");
    private static final Pattern DEX_ENTRY = Pattern.compile("\\[(SPECIES_\\w+)\\s*-\\s*1]\\s*=\\s*(NATIONAL_DEX_\\w+)");
    private static final Pattern FRONT_PIC = Pattern.compile("\\[(SPECIES_\\w+)]\\s*=\\s*\\{\\s*(gFrontSprite\\w+?)Tiles");
    // Reference ruleset lines (see GenerateRuleset's output)
    private static final Pattern REF_TYPE = Pattern.compile("^ {2}([A-Z]+): \\{.*EN: \"([^\"]*)\"");
    private static final Pattern REF_MOVE = Pattern.compile("^ {2}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }, type: (\\w+), power: (\\d+), category: (\\w+) }");
    private static final Pattern REF_SPECIES = Pattern.compile("^ {2}(\\d+):(?: \\{ name: \\{.*?EN: \"([^\"]*)\")?");
    private static final Pattern REF_SPECIES_NAME = Pattern.compile("^ {4}name: \\{.*?EN: \"([^\"]*)\"");
    private static final Pattern REF_FORM = Pattern.compile(
            "^ {6}(\\d+): \\{ name: \\{.*?EN: \"([^\"]*)\" }.*?sprite: \"([^\"]*)\"(?:, region: (\\w+))?");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String dpe;
    private final String cfru;
    private final Path reference;
    /** The output folder's name: prefix of the hack's own abilities. */
    private final String rulesetId;
    /** Name -> value as written (a number or another name; "1" for a bare #define). */
    private final Map<String, String> defines = new HashMap<>();

    private GenerateCfruRuleset(String dpe, String cfru, Path reference, String rulesetId, List<String> buildDefines)
            throws IOException, InterruptedException {
        this.dpe = dpe;
        this.cfru = cfru;
        this.reference = reference;
        this.rulesetId = rulesetId;
        buildDefines.forEach(define -> defines.put(define, "1"));
        preprocess(read(cfru, "src/config.h")); // collects the #defines
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java tools/GenerateCfruRuleset.java <dpeSource> <cfruSource> <referenceRuleset> <outputDir> [DEFINE...]");
            System.exit(1);
        }
        Path outputDir = Path.of(args[3]);
        Files.createDirectories(outputDir);
        List<String> buildDefines = List.of(args).subList(4, args.length);
        GenerateCfruRuleset generator = new GenerateCfruRuleset(args[0], args[1], Path.of(args[2]),
                outputDir.getFileName().toString(), buildDefines);
        String header = "# Generated by tools/GenerateCfruRuleset.java from DPE (" + args[0] + ") and CFRU (" + args[1]
                + ")" + (buildDefines.isEmpty() ? "" : ", defined: " + String.join(" ", buildDefines)) + " - do not edit by hand.\n";

        List<String> types = generator.types();
        Map<String, String> abilityNames = new TreeMap<>();
        Files.writeString(outputDir.resolve("type-chart.yaml"), generator.typeChartYaml(header, types), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("pokemon.yaml"), generator.pokemonYaml(header, Set.copyOf(types), abilityNames), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("moves.yaml"), generator.movesYaml(header, Set.copyOf(types)), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("abilities.yaml"), generator.abilitiesYaml(header, abilityNames), StandardCharsets.UTF_8);
        System.out.println("Ruleset written to " + outputDir.toAbsolutePath());
    }

    // ---------------------------------------------------------------- types and type chart

    /** The types Pokémon can have, in the game's id order. */
    private List<String> types() throws IOException, InterruptedException {
        Map<String, String> raw = constants(read(cfru, "include/constants/pokemon.h"), "");
        Matcher lastType = Pattern.compile("\\(?\\s*(\\w+)\\s*\\+\\s*1\\s*\\)?").matcher(raw.getOrDefault("NUMBER_OF_MON_TYPES", ""));
        if (!lastType.matches()) {
            throw new IllegalStateException("NUMBER_OF_MON_TYPES is not (TYPE_X + 1)");
        }
        int count = Integer.decode(resolve(lastType.group(1), raw)) + 1;
        Map<Integer, String> byId = new TreeMap<>();
        raw.forEach((constant, value) -> {
            String type = constant.replaceFirst("^TYPE_", "");
            if (constant.startsWith("TYPE_") && value.matches("0x[0-9a-fA-F]+|\\d+") && Integer.decode(value) < count
                    && !INTERNAL_TYPES.contains(type) && !type.startsWith("MUL_") && !type.equals("NAME_LENGTH")) {
                byId.put(Integer.decode(value), type);
            }
        });
        List<String> types = List.copyOf(byId.values());
        Map<String, String> names = referenceTypeNames();
        List<String> unknown = types.stream().filter(type -> !names.containsKey(type)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("New types (add them to PokemonType and theme.css, name them here): " + unknown);
        }
        return types;
    }

    private String typeChartYaml(String header, List<String> types) throws IOException, InterruptedException {
        String source = preprocess(read(cfru, "src/Tables/type_tables.h"));
        int start = source.indexOf("gTypeEffectiveness");
        if (start < 0) {
            throw new IllegalStateException("gTypeEffectiveness not found in type_tables.h");
        }
        Map<String, Map<String, String>> chart = new LinkedHashMap<>();
        Matcher block = TYPE_BLOCK.matcher(source.substring(start));
        while (block.find()) {
            Map<String, String> row = new LinkedHashMap<>();
            Matcher pair = TYPE_PAIR.matcher(block.group(2));
            while (pair.find()) {
                String multiplier = MULTIPLIERS.get(pair.group(2));
                if (multiplier == null) {
                    throw new IllegalStateException("Unknown multiplier " + pair.group(2));
                }
                row.put(pair.group(1), multiplier);
            }
            chart.put(block.group(1), row);
        }
        if (!chart.keySet().containsAll(types)) {
            throw new IllegalStateException("Type chart rows missing: " + types.stream().filter(type -> !chart.containsKey(type)).toList());
        }
        Map<String, String> names = referenceTypeNames();
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# effectiveness: attacking type -> defending type -> multiplier. Pairs not listed are 1x.\n");
        yaml.append("types:\n");
        types.forEach(type -> yaml.append("  ").append(type).append(": ").append(nameMap(names.get(type))).append('\n'));
        yaml.append("effectiveness:\n");
        for (String attacker : types) {
            List<String> entries = new ArrayList<>();
            for (String defender : types) {
                String multiplier = chart.get(attacker).getOrDefault(defender, "1");
                if (!multiplier.equals("1")) {
                    entries.add(defender + ": " + multiplier);
                }
            }
            yaml.append("  ").append(attacker).append(": { ").append(String.join(", ", entries)).append(" }\n");
        }
        return yaml.toString();
    }

    // ---------------------------------------------------------------- Pokémon

    private record HackSpecies(String constant, int id, int dex, String name, List<String> types, List<Integer> stats,
                               List<String> abilities, String sprite) {
    }

    private record ReferenceForm(int key, String name, String sprite, String region) {
    }

    private record ReferenceSpecies(String name, List<ReferenceForm> forms) {
    }

    private String pokemonYaml(String header, Set<String> types, Map<String, String> abilityNames) throws IOException, InterruptedException {
        Map<String, Integer> speciesIds = decodedConstants(read(dpe, "include/species.h"), "SPECIES_");
        Map<String, Integer> dexNumbers = decodedConstants(read(dpe, "include/pokedex.h"), "NATIONAL_DEX_");
        Map<String, Integer> speciesDex = new HashMap<>();
        Matcher dexEntry = DEX_ENTRY.matcher(preprocess(read(dpe, "src/Species_To_Pokdex_Table.c")));
        while (dexEntry.find()) {
            speciesDex.put(dexEntry.group(1), dexNumbers.getOrDefault(dexEntry.group(2), 0));
        }
        List<String> nameList = stringList(read(dpe, "strings/Pokemon_Name_Table.string"));
        Map<String, String> names = new HashMap<>();
        speciesIds.forEach((constant, id) -> {
            if (id < nameList.size()) {
                names.put(constant, nameList.get(id));
            }
        });
        Map<String, String> sprites = frontPics();
        Map<String, Integer> abilityIds = cachedAbilityIds();
        List<String> abilityNameList = cachedAbilityNames();
        Map<String, String> pokeApiAbilities = pokeApiAbilities();

        // All species of each dex number; the lowest id is the base form (DPE lists alternate forms after all species)
        Map<Integer, List<HackSpecies>> byDex = new TreeMap<>();
        for (Map.Entry<String, String> entry : entries(preprocess(read(dpe, "src/Base_Stats.c"))).entrySet()) {
            String constant = entry.getKey();
            Integer id = speciesIds.get(constant);
            int dex = speciesDex.getOrDefault(constant, 0);
            if (id == null || dex == 0 || BATTLE_ONLY_FORM.matcher(constant).find()) {
                continue;
            }
            byDex.computeIfAbsent(dex, d -> new ArrayList<>())
                    .add(hackSpecies(constant, id, dex, entry.getValue(), names, sprites, types, abilityIds, abilityNameList));
        }
        byDex.values().forEach(list -> list.sort((a, b) -> Integer.compare(a.id(), b.id())));
        Map<Integer, ReferenceSpecies> referenceSpecies = referenceSpecies();
        Map<String, String> speciesIdentifiers = pokeApiColumns("pokemon_species", "id", "identifier");
        Map<String, String> pokemonIdentifiers = pokeApiColumns("pokemon", "id", "identifier");
        Set<String> spriteNames = new TreeSet<>();

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Key: national dex number. name: as shown in the game. sprite: the hack's own sprite (graphics/<sprite>.png).\n");
        yaml.append("# forms: keys, names and regions as in the reference ruleset; types, stats, abilities and sprites from the hack.\n");
        yaml.append("# stats: HP, Attack, Defense, Sp. Atk, Sp. Def, Speed. abilities: both slots and the hidden one.\n");
        yaml.append("pokemon:\n");
        for (Map.Entry<Integer, List<HackSpecies>> entry : byDex.entrySet()) {
            int dex = entry.getKey();
            ReferenceSpecies ref = referenceSpecies.get(dex);
            if (ref == null) {
                throw new IllegalStateException("Dex #" + dex + " is not in the reference ruleset");
            }
            HackSpecies species = entry.getValue().getFirst();
            String identifier = speciesIdentifiers.get(String.valueOf(dex));
            // The base form may carry its form name (SPECIES_BASCULIN_RED)
            if (!normalize(species.constant().substring("SPECIES_".length())).startsWith(normalize(identifier))) {
                throw new IllegalStateException("Dex #" + dex + " is " + species.constant() + " in the hack, " + identifier + " in PokeAPI");
            }
            if (!species.name().equals(ref.name())) {
                System.out.println("  name #" + dex + ": " + species.name() + " (" + ref.name() + ")");
            }
            yaml.append("  ").append(dex).append(":\n");
            yaml.append("    name: ").append(nameMap(species.name())).append('\n');
            yaml.append("    types: ").append(species.types()).append('\n');
            yaml.append("    stats: ").append(species.stats()).append('\n');
            List<String> abilities = abilitySlugs(species, abilityNames, pokeApiAbilities);
            if (!abilities.isEmpty()) {
                yaml.append("    abilities: [").append(String.join(", ", abilities)).append("]\n");
            }
            yaml.append("    sprite: ").append(quote(species.sprite())).append('\n');
            spriteNames.add(species.sprite());

            List<HackSpecies> forms = entry.getValue().subList(1, entry.getValue().size());
            List<String> formLines = new ArrayList<>();
            for (ReferenceForm form : ref.forms()) {
                String formIdentifier = formIdentifier(form, pokemonIdentifiers, dex);
                HackSpecies data = formData(formIdentifier, forms);
                if (data == null) {
                    System.out.println("  form " + formIdentifier + ": not in the hack, left out");
                    continue;
                }
                StringBuilder line = new StringBuilder("      ").append(form.key()).append(": { name: ").append(nameMap(form.name()))
                        .append(", types: ").append(data.types()).append(", sprite: ").append(quote(data.sprite()));
                if (form.region() != null) {
                    line.append(", region: ").append(form.region());
                }
                line.append(", stats: ").append(data.stats());
                List<String> formAbilities = abilitySlugs(data, abilityNames, pokeApiAbilities);
                if (!formAbilities.isEmpty()) {
                    line.append(", abilities: [").append(String.join(", ", formAbilities)).append(']');
                }
                formLines.add(line.append(" }").toString());
                spriteNames.add(data.sprite());
            }
            if (!formLines.isEmpty()) {
                yaml.append("    forms:\n");
                formLines.forEach(line -> yaml.append(line).append('\n'));
            }
        }
        System.out.println("  " + byDex.size() + " species (dex #1-#" + ((TreeMap<Integer, ?>) byDex).lastKey() + ")");
        String result = yaml.toString();
        for (Map.Entry<String, String> sprite : spriteFolders(spriteNames).entrySet()) {
            result = result.replace(quote(sprite.getKey()), quote(sprite.getValue() + "/" + sprite.getKey()));
        }
        return result;
    }

    private HackSpecies hackSpecies(String constant, int id, int dex, String body, Map<String, String> names, Map<String, String> sprites,
                                    Set<String> types, Map<String, Integer> abilityIds, List<String> abilityNameList) {
        List<Integer> stats = new ArrayList<>();
        for (String stat : STATS) {
            stats.add(Integer.parseInt(field(body, stat, constant)));
        }
        List<String> speciesTypes = new ArrayList<>();
        for (String field : List.of("type1", "type2")) {
            String type = field(body, field, constant).replaceFirst("^TYPE_", "");
            if (!types.contains(type)) {
                throw new IllegalStateException(constant + ": unknown type " + type);
            }
            if (!speciesTypes.contains(type)) {
                speciesTypes.add(type); // TYPE_FIRE twice = pure Fire
            }
        }
        List<String> abilities = new ArrayList<>();
        for (String field : List.of("ability1", "ability2", "hiddenAbility")) {
            String ability = field(body, field, constant);
            if (!ability.equals("ABILITY_NONE") && !abilities.contains(ability)) {
                Integer abilityId = abilityIds.get(ability);
                if (abilityId == null || abilityId >= abilityNameList.size()) {
                    throw new IllegalStateException(constant + ": unknown ability " + ability);
                }
                abilities.add(ability);
            }
        }
        String name = names.get(constant);
        String sprite = sprites.get(constant);
        if (name == null || sprite == null) {
            throw new IllegalStateException(constant + ": name " + name + ", sprite " + sprite);
        }
        return new HackSpecies(constant, id, dex, name, List.copyOf(speciesTypes), List.copyOf(stats), List.copyOf(abilities), sprite);
    }

    /**
     * Ability constants -> PokeAPI identifiers, by constant ("ABILITY_LIGHTNINGROD" = lightning-rod) or else by English
     * name; the hack's own abilities get "&lt;ruleset&gt;-&lt;constant&gt;" (no effect PokeSight knows).
     */
    private List<String> abilitySlugs(HackSpecies species, Map<String, String> abilityNames, Map<String, String> pokeApiAbilities)
            throws IOException, InterruptedException {
        Map<String, Integer> abilityIds = cachedAbilityIds();
        List<String> nameList = cachedAbilityNames();
        List<String> slugs = new ArrayList<>();
        for (String constant : species.abilities()) {
            String name = nameList.get(abilityIds.get(constant));
            String key = normalize(constant.substring("ABILITY_".length()));
            String slug = ABILITY_ALIASES.getOrDefault(constant, pokeApiAbilities.get(key));
            if (slug == null) {
                slug = pokeApiAbilities.getOrDefault(NAME_KEY + normalize(name), rulesetId + "-" + key);
            }
            abilityNames.putIfAbsent(slug, name);
            slugs.add(slug);
        }
        return slugs;
    }

    private Map<String, Integer> abilityIdCache;
    private List<String> abilityNameCache;

    private Map<String, Integer> cachedAbilityIds() throws IOException, InterruptedException {
        if (abilityIdCache == null) {
            abilityIdCache = decodedConstants(read(dpe, "include/abilities.h"), "ABILITY_");
        }
        return abilityIdCache;
    }

    private List<String> cachedAbilityNames() throws IOException, InterruptedException {
        if (abilityNameCache == null) {
            abilityNameCache = stringList(read(cfru, "strings/ability_name_table.string"));
        }
        return abilityNameCache;
    }

    /** PokeAPI abilities: normalized identifier -> identifier, and {@link #NAME_KEY} + English name -> identifier where unique. */
    private Map<String, String> pokeApiAbilities() throws IOException, InterruptedException {
        Map<String, String> identifiers = pokeApiColumns("abilities", "id", "identifier");
        Map<String, String> abilities = new HashMap<>();
        identifiers.values().forEach(identifier -> abilities.put(normalize(identifier), identifier));
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
     * The hack's form (of the same dex number) for a PokeAPI form identifier: the exact match after DPE's short region
     * suffixes (RATTATA_A = rattata-alola), else the shortest form whose parts contain the identifier's or are contained
     * in them ("tauros-paldea-combat-breed" = TAUROS_PALDEA_COMBAT); null if none.
     */
    private static HackSpecies formData(String identifier, List<HackSpecies> forms) {
        List<String> wanted = List.of(identifier.split("-"));
        HackSpecies best = null;
        for (HackSpecies candidate : forms) {
            List<String> tokens = formTokens(candidate.constant());
            if (tokens.equals(wanted)) {
                return candidate;
            }
            boolean related = tokens.getFirst().equals(wanted.getFirst()) && (wanted.containsAll(tokens) || tokens.containsAll(wanted));
            if (related && (best == null || tokens.size() < formTokens(best.constant()).size())) {
                best = candidate;
            }
        }
        if (best != null) {
            System.out.println("  form " + identifier + " = " + best.constant());
        }
        return best;
    }

    private static List<String> formTokens(String constant) {
        if (FORM_ALIASES.containsKey(constant)) {
            return List.of(FORM_ALIASES.get(constant).split("-"));
        }
        List<String> tokens = new ArrayList<>();
        for (String token : constant.substring("SPECIES_".length()).toLowerCase().split("_")) {
            tokens.add(tokens.isEmpty() ? token : FORM_TOKENS.getOrDefault(token, token));
        }
        return tokens;
    }

    /** Species constant -> sprite name below graphics/ ("frontspr/gFrontSprite003Venusaur"), without checking the folder yet. */
    private Map<String, String> frontPics() throws IOException, InterruptedException {
        Map<String, String> sprites = new HashMap<>();
        Matcher entry = FRONT_PIC.matcher(preprocess(read(dpe, "src/Front_Pic_Table.c")));
        while (entry.find()) {
            sprites.put(entry.group(1), entry.group(2));
        }
        return sprites;
    }

    /** Sprite symbol -> its folder below graphics/ (frontspr, castform), found by asking for the file; every sprite must exist. */
    private Map<String, String> spriteFolders(Set<String> sprites) throws InterruptedException {
        List<String> missing = new ArrayList<>();
        Map<String, String> folders = new HashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore slots = new Semaphore(16);
            for (String sprite : sprites) {
                executor.submit(() -> {
                    slots.acquire();
                    try {
                        for (String folder : SPRITE_FOLDERS) {
                            if (exists(dpe, SPRITE_PATH.replace("{name}", folder + "/" + sprite))) {
                                synchronized (folders) {
                                    folders.put(sprite, folder);
                                }
                                return null;
                            }
                        }
                        synchronized (missing) {
                            missing.add(sprite);
                        }
                    } finally {
                        slots.release();
                    }
                    return null;
                });
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Sprites missing: " + new TreeSet<>(missing));
        }
        System.out.println("  " + sprites.size() + " sprites found (" + SPRITE_PATH + ")");
        return folders;
    }

    // ---------------------------------------------------------------- moves

    private String movesYaml(String header, Set<String> types) throws IOException, InterruptedException {
        Map<Integer, String> referenceMoves = new HashMap<>();
        for (String line : Files.readAllLines(reference.resolve("moves.yaml"), StandardCharsets.UTF_8)) {
            Matcher move = REF_MOVE.matcher(line);
            if (move.find()) {
                referenceMoves.put(Integer.parseInt(move.group(1)), move.group(5));
            }
        }
        Map<String, String> pokeApiMoves = new HashMap<>(); // normalized identifier or NAME_KEY + name -> id
        pokeApiColumns("moves", "identifier", "id").forEach((identifier, id) -> pokeApiMoves.put(normalize(identifier), id));
        for (String line : csvLines("move_names")) {
            String[] values = line.split(",", 3); // move_id,local_language_id,name
            if (values.length == 3 && values[1].equals(ENGLISH_ID)) {
                pokeApiMoves.putIfAbsent(NAME_KEY + normalize(values[2]), values[0]);
            }
        }
        Map<String, Integer> moveIds = decodedConstants(read(cfru, "include/constants/moves.h"), "MOVE_");
        List<String> names = stringList(read(cfru, "strings/attack_name_table.string"));
        Matcher lastMove = NON_Z_MOVE_COUNT.matcher(read(cfru, "include/constants/moves.h"));
        if (!lastMove.find()) {
            throw new IllegalStateException("NON_Z_MOVE_COUNT not found in moves.h");
        }
        int moveCount = moveIds.get(lastMove.group(1)) + 1; // Z-Moves and Max Moves follow

        Map<Integer, String> moves = new TreeMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> own = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries(preprocess(read(cfru, "src/Tables/battle_moves.c"))).entrySet()) {
            String constant = entry.getKey();
            String body = entry.getValue();
            Integer hackId = moveIds.get(constant);
            if (!body.contains(".power") || !body.contains(".split") || hackId == null || hackId == 0 || hackId >= moveCount) {
                continue; // other tables in the file, MOVE_NONE, Z-Moves and Max Moves
            }
            String split = field(body, "split", constant);
            int power = Integer.parseInt(field(body, "power", constant));
            if (power <= MAX_PLACEHOLDER_POWER || split.equals("SPLIT_STATUS")) {
                continue;
            }
            String name = names.get(hackId);
            String id = pokeApiMoves.get(normalize(constant.substring("MOVE_".length())));
            if (id == null) {
                id = pokeApiMoves.get(NAME_KEY + normalize(name));
            }
            if (id == null && OWN_MOVES.contains(constant)) {
                id = String.valueOf(NEW_MOVE_KEY_BASE + hackId);
                own.add(name);
            } else if (id == null) {
                unknown.add(constant + " (" + name + ")");
                continue;
            }
            int key = Integer.parseInt(id);
            if (key < NEW_MOVE_KEY_BASE && !referenceMoves.containsKey(key)) {
                continue; // Z-Move, Max Move or variable power in the reference
            }
            String type = field(body, "type", constant).replaceFirst("^TYPE_", "");
            if (INTERNAL_TYPES.contains(type)) {
                System.out.println("  " + name + ": left out (typeless: " + type + ")"); // Struggle
                continue;
            }
            String category = CATEGORIES.get(split);
            if (!types.contains(type) || category == null || name.isBlank()) {
                throw new IllegalStateException(constant + ": type " + type + ", category " + split + ", name '" + name + "'");
            }
            moves.put(key, "{ name: " + nameMap(name) + ", type: " + type + ", power: " + power + ", category: " + category + " }");
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Damaging moves PokeAPI does not know (add a rule for the hack's own moves): " + unknown);
        }
        System.out.println("  the hack's own damaging moves: " + own);
        Set<Integer> missing = new TreeSet<>(referenceMoves.keySet());
        missing.removeAll(moves.keySet());
        System.out.println("  " + moves.size() + " damaging moves; reference moves the hack lacks or has without power: " + missing);

        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# Damaging moves with a fixed power. Key: move id (PokeAPI); ").append(NEW_MOVE_KEY_BASE)
                .append(" + the hack's move id for its own moves. name: as shown in the game.\n");
        yaml.append("moves:\n");
        moves.forEach((id, move) -> yaml.append("  ").append(id).append(": ").append(move).append('\n'));
        return yaml.toString();
    }

    // ---------------------------------------------------------------- abilities

    private String abilitiesYaml(String header, Map<String, String> names) {
        StringBuilder yaml = new StringBuilder(header);
        yaml.append("# The generation, which decides how abilities work, and the names of the abilities in pokemon.yaml.\n");
        yaml.append("# Identifiers as in PokeAPI; \"").append(rulesetId).append("-...\" = made up by the hack (none of the effects PokeSight knows).\n");
        yaml.append("generation: ").append(GENERATION).append('\n');
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

    private final Map<String, List<String>> csvCache = new HashMap<>();

    private List<String> csvLines(String file) throws IOException, InterruptedException {
        List<String> lines = csvCache.get(file);
        if (lines == null) {
            lines = download(POKEAPI_CSV + file + ".csv").lines().filter(line -> !line.isBlank()).toList();
            csvCache.put(file, lines);
        }
        return lines;
    }

    // ---------------------------------------------------------------- C source parsing

    /**
     * The lines of a C file that survive the preprocessor ({@code #if A op B}, {@code #if A}, {@code #ifdef},
     * {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}), without comments; active {@code #define}s are
     * collected (value as written, "1" for a bare {@code #define NAME}).
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
                    defines.put(define.group(1), define.group(2) == null ? "1" : define.group(2).trim());
                }
            } else if (directive.startsWith("#") && !directive.startsWith("#include") && !directive.startsWith("#undef")
                    && !directive.startsWith("#pragma")) {
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
        Matcher defined = Pattern.compile("!?\\s*defined\\s*\\(?\\s*(\\w+)\\s*\\)?").matcher(expression);
        if (defined.matches()) {
            return expression.startsWith("!") != defines.containsKey(defined.group(1));
        }
        if (expression.matches("\\w+")) {
            return value(expression) != 0;
        }
        throw new IllegalStateException("Unsupported condition: " + expression);
    }

    private int value(String token) {
        String value = token;
        for (int depth = 0; !value.matches("-?\\d+|0x[0-9a-fA-F]+"); depth++) {
            if (value.equals("TRUE") || value.equals("FALSE")) {
                return value.equals("TRUE") ? 1 : 0;
            }
            value = defines.get(value);
            if (value == null || depth > 20) {
                throw new IllegalStateException("Not defined: " + token);
            }
        }
        return Integer.decode(value);
    }

    /** {@code #define <prefix>NAME value} lines: name -> raw value (comments removed). */
    private static Map<String, String> constants(String source, String prefix) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (String raw : source.lines().toList()) {
            Matcher define = DEFINE.matcher(raw.replaceAll("//.*", "").trim());
            if (define.matches() && define.group(1).startsWith(prefix) && define.group(2) != null) {
                constants.put(define.group(1), define.group(2).trim());
            }
        }
        return constants;
    }

    /** {@code #define <prefix>NAME 0x1A} constants (also through other constants of the file) -> their values. */
    private static Map<String, Integer> decodedConstants(String source, String prefix) {
        Map<String, String> raw = constants(source, "");
        Map<String, Integer> values = new HashMap<>();
        for (String constant : raw.keySet()) {
            if (constant.startsWith(prefix)) {
                try {
                    values.put(constant, Integer.decode(resolve(raw.get(constant), raw)));
                } catch (IllegalStateException | NumberFormatException e) {
                    // an expression such as (SPECIES_X + 1): not a species/move/ability id
                }
            }
        }
        return values;
    }

    private static String resolve(String value, Map<String, String> raw) {
        String current = value;
        for (int depth = 0; !current.matches("-?\\d+|0x[0-9a-fA-F]+"); depth++) {
            current = raw.get(current);
            if (current == null || depth > 20) {
                throw new IllegalStateException("Not a number: " + value);
            }
        }
        return current;
    }

    /** Designated initializers {@code [CONSTANT] = ...} in source order: constant -> body up to the next one. */
    private static Map<String, String> entries(String source) {
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(source);
        String constant = null;
        int bodyStart = 0;
        while (matcher.find()) {
            if (constant != null) {
                entries.putIfAbsent(constant, source.substring(bodyStart, matcher.start()));
            }
            constant = matcher.group(1);
            bodyStart = matcher.end();
        }
        if (constant != null) {
            entries.putIfAbsent(constant, source.substring(bodyStart));
        }
        return entries;
    }

    /**
     * A string table ({@code #org @NAME_X} + text line) in id order; the first entry is id 0. The labels do not always
     * match the constants ({@code NAME_NIDORANF} = {@code SPECIES_NIDORAN_F}), the order does.
     */
    private static List<String> stringList(String source) {
        List<String> names = new ArrayList<>();
        Matcher entry = STRING_ENTRY.matcher(source);
        while (entry.find()) {
            String name = entry.group(1).trim();
            for (Map.Entry<String, String> character : CHARACTERS.entrySet()) {
                name = name.replace(character.getKey(), character.getValue());
            }
            if (name.contains("[")) {
                throw new IllegalStateException("Unknown character code in " + name + " (see charmap.tbl)");
            }
            names.add(name);
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

    private static String normalize(String name) {
        return name.toLowerCase().replace("é", "e").replaceAll("[^a-z0-9]", "");
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
        return base.startsWith("http") ? download(base + "/" + path.replace(" ", "%20"))
                : Files.readString(Path.of(base, path), StandardCharsets.UTF_8);
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
