import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the data behind the catch star: {@code pokedex.yaml} (evolutions, legendary and mythical Pokémon, from the
 * PokeAPI CSV dump) and {@code tiers/<id>.yaml} (Smogon's in-game tier lists).
 *
 * <p>Usage: {@code java tools/GenerateCatchData.java src/main/resources}
 *
 * <p>Smogon's published in-game guides are downloaded and parsed (two page layouts: the newer guides with
 * {@code <h3 id="s-tier">} / {@code <h4 id="alakazam">Abra (Trade)}, the older ones with {@code <h2><a id="high">} /
 * {@code <h3>Abra}). The newer lists were only posted in the forum, whose pages are not readable by scripts; their
 * starred tiers (S and A) are transcribed below from the thread's tier list post.
 *
 * <p>Every entry is resolved to national dex numbers and checked: an unknown name, or an evolution path that does
 * not exist (Eevee to Gengar), stops the run.
 */
public class GenerateCatchData {

    private static final String CSV_BASE = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/";
    private static final String GUIDE_BASE = "https://www.smogon.com/ingame/guides/";
    private static final String FORUM_BASE = "https://www.smogon.com/forums/threads/";
    private static final String USER_AGENT = "PokeSight tier generator";
    private static final List<String> LETTER_TIERS = List.of("S", "A");
    private static final List<String> HIGH_TIER = List.of("High");
    /** Notes in an entry's parentheses that neither restrict versions nor name an evolution path. */
    private static final Set<String> PLAIN_NOTES = Set.of("both", "50% form");
    /** Words of version notes besides the version names ("Ruby and Sapphire only"). */
    private static final Set<String> VERSION_FILLER = Set.of("and", "only", "version");
    /**
     * Rare one-off encounters PokeAPI flags neither legendary nor mythical. Sources: Bulbapedia, "Ultra Beast" and
     * "Paradox Pokémon".
     */
    private static final Set<String> ULTRA_BEASTS = Set.of("nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela",
            "kartana", "guzzlord", "poipole", "naganadel", "stakataka", "blacephalon");
    private static final Set<String> PARADOX = Set.of("great-tusk", "scream-tail", "brute-bonnet", "flutter-mane",
            "slither-wing", "sandy-shocks", "iron-treads", "iron-bundle", "iron-hands", "iron-jugulis", "iron-moth",
            "iron-thorns", "roaring-moon", "iron-valiant", "walking-wake", "iron-leaves", "gouging-fire", "raging-bolt",
            "iron-boulder", "iron-crown");

    private static final Pattern NEW_TIER = Pattern.compile("(?i)^([SABCDEF])-[TТ]ier$"); // FRLG uses a Cyrillic Т
    private static final Pattern NEW_HEADING = Pattern.compile("<h([34])(?: id=\"([^\"]*)\")?>([^<]*)");
    private static final Pattern OLD_HEADING = Pattern.compile("<h2><a (?:id|name)=\"(high|middle|low|bottom|untiered)\">|<h3>([^<]+)</h3>");
    private static final Pattern TITLE = Pattern.compile("<h1[^>]*>([^<]+)");
    /** "By Fireburn. Art by Blue Frog." - the first sentence names the writers. */
    private static final Pattern AUTHOR = Pattern.compile("(?s)<div class=\"author\">\\s*By (.*?)</div>");
    private static final Pattern NOTE = Pattern.compile("^(.+?)\\s*\\(([^)]*)\\)\\s*$");

    /**
     * A published guide.
     *
     * @param games    the PokeSight games the list is for
     * @param versions version names used in its notes ("RB", "Ruby") and the game each belongs to
     */
    record Guide(String id, String page, int lastDex, List<String> starred, List<String> games, Map<String, String> versions) {
    }

    /** A forum list; {@code tiers} holds the transcribed starred tiers, see {@link #entry}. */
    record ForumList(String id, String title, String author, String thread, int lastDex, List<String> games,
                     Map<String, String> versions, Map<String, List<Raw>> tiers) {
    }

    /**
     * An entry as the source writes it.
     *
     * @param target  the evolution an entry with a path note is rated for (Abra (Trade): alakazam); PokeAPI identifier
     * @param species the species if the heading is not a plain name (Totem Alolan Marowak); PokeAPI identifier
     */
    record Raw(String heading, String target, String species) {
    }

    record Entry(String tier, String name, int species, Integer upTo, List<String> games) {
    }

    private static final List<Guide> GUIDES = List.of(
            new Guide("rby", "rby_ingametiers", 151, LETTER_TIERS, List.of("red-blue", "yellow"),
                    Map.of("rb", "red-blue", "r", "red-blue", "b", "red-blue", "y", "yellow")),
            new Guide("rse", "adv_rse_ingametiers", 386, HIGH_TIER, List.of("ruby-sapphire", "emerald"),
                    Map.of("ruby", "ruby-sapphire", "sapphire", "ruby-sapphire", "emerald", "emerald")),
            new Guide("frlg", "adv_frlg_ingametiers", 386, LETTER_TIERS, List.of("firered-leafgreen"), Map.of()),
            new Guide("dp", "dpp_ingametiers", 493, HIGH_TIER, List.of("diamond-pearl"),
                    Map.of("diamond", "diamond-pearl", "pearl", "diamond-pearl")),
            new Guide("platinum", "dpp_plat_ingametiers", 493, HIGH_TIER, List.of("platinum", "platinum-together"), Map.of()),
            new Guide("hgss", "hgss_ingame_tier", 493, LETTER_TIERS, List.of("heartgold-soulsilver"), Map.of()),
            new Guide("bw", "bw_ingametiers", 649, LETTER_TIERS, List.of("black-white"), Map.of()));

    /** Transcribed on 2026-11-16 from the tier list post of each thread (first post, or the second where the first is the intro). */
    private static final List<ForumList> FORUM_LISTS = List.of(
            new ForumList("gsc", "GSC In-Game Tier List Mark V", "Turdterra", "gsc-in-game-tier-list-mark-v.3687563/",
                    251, List.of("gold-silver", "crystal"), Map.of("gsc", "gold-silver", "g", "gold-silver", "s", "gold-silver",
                    "gs", "gold-silver", "c", "crystal"), tiers(
                    List.of(entry("Abra (Both)")),
                    List.of(entry("Magmar"), entry("Mareep"), entry("Spearow"), entry("Teddiursa (C)"), entry("Totodile")))),
            new ForumList("b2w2", "Pokémon Black 2 and White 2 In-Game Tier List Reboot", "ceal",
                    "pokemon-black-2-and-white-2-in-game-tier-list-reboot.3705068/", 649, List.of("black-2-white-2"), Map.of(), tiers(
                    names("Braviary", "Darumaka", "Drilbur", "Heracross", "Magnemite", "Minccino"),
                    names("Axew", "Cobalion", "Conkeldurr", "Espeon", "Jolteon", "Vaporeon", "Elekid", "Lillipup", "Litwick",
                            "Oshawott", "Petilil", "Pinsir", "Riolu", "Roselia", "Sandile", "Scraggy", "Sigilyph", "Tepig",
                            "Terrakion", "Virizion", "Zorua"))),
            new ForumList("oras", "ORAS Tier List V2.0", "Colonel M", "oras-tier-list-v2-0.3532211/", 721,
                    List.of("omega-ruby-alpha-sapphire"), Map.of("or", "omega-ruby-alpha-sapphire", "as", "omega-ruby-alpha-sapphire"), tiers(
                    List.of(entry("Abra (Alakazam)", "alakazam"), entry("Magikarp"), entry("Mudkip"), entry("Torchic")),
                    List.of(entry("Absol"), entry("Groudon"), entry("Heracross"), entry("Kyogre"), entry("Latias (AS)"),
                            entry("Latios (OR)"), entry("Magnemite"), entry("Makuhita"), entry("Marill"), entry("Mawile"),
                            entry("Meditite"), entry("Numel"), entry("Ralts (Gardevoir)", "gardevoir"), entry("Shroomish"),
                            entry("Treecko")))),
            new ForumList("sm", "Sun & Moon In-Game Tier List", "Colonel M", "sun-moon-in-game-tier-list.3587886/", 802,
                    List.of("sun-moon"), Map.of(), tiers(
                    names("Magikarp", "Magnemite", "Popplio", "Wingull"),
                    List.of(entry("Abra"), entry("Archen"), entry("Cutiefly"), entry("Dewpider"), entry("Gastly (Gengar)", "gengar"),
                            entry("Grimer"), entry("Growlithe"), entry("Litten"), entry("Magby (Magmortar)", "magmortar"),
                            entry("Makuhita"), entry("Mankey"), entry("Mudbray"), entry("Munchlax"), entry("Salandit"),
                            entry("Staryu"), entry("Stufful"), entry("Wishiwashi"), entry("Zubat"), entry("Zygarde (50% Form)")))),
            new ForumList("usum", "Pokémon Ultra Sun and Ultra Moon In-Game Tier List v2", "Ryota Mitarai",
                    "pokemon-ultra-sun-and-ultra-moon-in-game-tier-list-v2.3676552/", 807, List.of("ultra-sun-ultra-moon"), Map.of(), tiers(
                    names("Hawlucha", "Mudbray", "Oricorio"),
                    List.of(entry("Charmander"), entry("Magikarp"), entry("Magnemite"), entry("Makuhita"), entry("Munchlax"),
                            entry("Pikipek"), entry("Popplio"), entry("Stufful"), new Raw("Totem Alolan Marowak", null, "marowak"),
                            entry("Wingull"), entry("Wishiwashi")))),
            new ForumList("bdsp", "Pokémon Brilliant Diamond and Shining Pearl In-Game Tier List", "MeepBard",
                    "pok%C3%A9mon-brilliant-diamond-and-shining-pearl-in-game-tier-list.3724116/", 493,
                    List.of("brilliant-diamond-shining-pearl"), Map.of(), tiers(
                    List.of(entry("Abra (no trade)", "kadabra"), entry("Abra (trade)", "alakazam"), entry("Chimchar"),
                            entry("Gastly (trade)", "gengar"), entry("Jirachi"), entry("Mew"), entry("Scyther (trade)", "scizor"),
                            entry("Starly")),
                    List.of(entry("Absol"), entry("Azelf"), entry("Budew"), entry("Buizel"), entry("Cranidos"), entry("Croagunk"),
                            entry("Dialga"), entry("Elekid (trade)", "electivire"), entry("Gastly (no trade)", "haunter"),
                            entry("Houndoom"), entry("Magby (trade)", "magmortar"), entry("Magikarp"), entry("Magnemite"),
                            entry("Marill"), entry("Misdreavus"), entry("Palkia"), entry("Piplup"), entry("Pinsir"),
                            entry("Ralts (Gardevoir)", "gardevoir"), entry("Scyther (no trade)", "scyther"), entry("Shinx"),
                            entry("Turtwig"), entry("Zubat")))));

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Normalized name (letters and digits) to national dex number. */
    private final Map<String, Integer> dexByName = new HashMap<>();
    private final Map<Integer, Integer> evolvesFrom = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java tools/GenerateCatchData.java <resources dir>");
            System.exit(2);
        }
        new GenerateCatchData().run(Path.of(args[0]));
    }

    private void run(Path resources) throws IOException, InterruptedException {
        Files.writeString(resources.resolve("pokedex.yaml"), pokedex(), StandardCharsets.UTF_8);
        Path tiers = Files.createDirectories(resources.resolve("tiers"));
        for (Guide guide : GUIDES) {
            String url = GUIDE_BASE + guide.page();
            String html = download(url);
            List<Entry> entries = html.contains("<h4") ? parseNewLayout(html, guide) : parseOldLayout(html, guide);
            requireStarred(guide.id(), entries, guide.starred());
            Files.writeString(tiers.resolve(guide.id() + ".yaml"), tierYaml(unescape(match(TITLE, html, url)), authors(html, url),
                    url, guide.lastDex(), guide.starred(), entries, "Generated by tools/GenerateCatchData.java from "
                            + url + " - do not edit by hand."), StandardCharsets.UTF_8);
            System.out.println(guide.id() + ": " + entries.size() + " entries");
        }
        for (ForumList list : FORUM_LISTS) {
            List<Entry> entries = new ArrayList<>();
            list.tiers().forEach((tier, raws) -> raws.forEach(raw -> entries.add(resolve(tier, raw, list.games(), list.versions()))));
            requireStarred(list.id(), entries, LETTER_TIERS);
            Files.writeString(tiers.resolve(list.id() + ".yaml"), tierYaml(list.title(), list.author(), FORUM_BASE + list.thread(),
                    list.lastDex(), LETTER_TIERS, entries, "Generated by tools/GenerateCatchData.java from the S and A tiers of "
                            + "the forum thread, transcribed by hand (the forum is not readable by scripts) - do not edit by hand."),
                    StandardCharsets.UTF_8);
            System.out.println(list.id() + ": " + entries.size() + " entries (forum)");
        }
    }

    // ---- PokeAPI ----

    private String pokedex() throws IOException, InterruptedException {
        Map<Integer, String> lines = new TreeMap<>();
        int lastDex = 0;
        Set<String> grouped = new HashSet<>();
        for (Map<String, String> row : csv("pokemon_species")) {
            int dex = Integer.parseInt(row.get("id"));
            String identifier = row.get("identifier");
            lastDex = Math.max(lastDex, dex);
            dexByName.put(key(identifier), dex);
            List<String> fields = new ArrayList<>();
            if (!row.get("evolves_from_species_id").isEmpty()) {
                int from = Integer.parseInt(row.get("evolves_from_species_id"));
                evolvesFrom.put(dex, from);
                fields.add("from: " + from);
            }
            String group = "1".equals(row.get("is_legendary")) ? "LEGENDARY"
                    : "1".equals(row.get("is_mythical")) ? "MYTHICAL"
                    : ULTRA_BEASTS.contains(identifier) ? "ULTRA_BEAST"
                    : PARADOX.contains(identifier) ? "PARADOX" : null;
            if (group != null) {
                fields.add("group: " + group);
                grouped.add(identifier);
            }
            if (!fields.isEmpty()) {
                lines.put(dex, "  " + dex + ": { " + String.join(", ", fields) + " }\n");
            }
        }
        Set<String> missing = new HashSet<>(ULTRA_BEASTS);
        missing.addAll(PARADOX);
        missing.removeAll(grouped);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unknown or legendary Ultra Beast / Paradox Pokémon: " + missing);
        }
        StringBuilder yaml = new StringBuilder("""
                # Generated by tools/GenerateCatchData.java from PokeAPI (https://pokeapi.co) - do not edit by hand.
                # lastDex: the highest national dex number; ROM hacks' own slots after it are unknown species.
                # Key: national dex number. from: the species it evolves from.
                # group: LEGENDARY / MYTHICAL as PokeAPI flags them; ULTRA_BEAST and PARADOX (Bulbapedia's lists).
                # Species with neither (first stages outside these groups) are left out.
                """);
        yaml.append("lastDex: ").append(lastDex).append("\nspecies:\n");
        lines.values().forEach(yaml::append);
        return yaml.toString();
    }

    private List<Map<String, String>> csv(String name) throws IOException, InterruptedException {
        List<String> lines = download(CSV_BASE + name + ".csv").lines().filter(line -> !line.isBlank()).toList();
        List<String> header = Arrays.asList(lines.getFirst().split(",", -1));
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",", -1); // pokemon_species has no quoted fields
            if (values.length != header.size()) {
                throw new IllegalStateException(name + ".csv: unexpected line " + line);
            }
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < values.length; i++) {
                row.put(header.get(i), values[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    // ---- Smogon pages ----

    /** Newer guides: {@code <h3 id="s-tier">S-Tier}, then {@code <h4 id="alakazam">Abra (Trade)} per entry. */
    private List<Entry> parseNewLayout(String html, Guide guide) {
        List<Entry> entries = new ArrayList<>();
        String tier = null;
        Matcher heading = NEW_HEADING.matcher(html);
        while (heading.find()) {
            String text = unescape(heading.group(3)).strip();
            if (heading.group(1).equals("3")) {
                Matcher letter = NEW_TIER.matcher(text);
                tier = letter.matches() ? letter.group(1).toUpperCase(Locale.ROOT) : text.equals("Untiered") ? "Untiered" : null;
            } else if (tier != null) {
                entries.add(resolve(tier, new Raw(text, heading.group(2), null), guide.games(), guide.versions()));
            }
        }
        return entries;
    }

    /** Older guides: {@code <h2><a id="high">High Tier</a></h2>}, then {@code <h3>Abra</h3>} per entry. */
    private List<Entry> parseOldLayout(String html, Guide guide) {
        List<Entry> entries = new ArrayList<>();
        String tier = null;
        Matcher heading = OLD_HEADING.matcher(html);
        while (heading.find()) {
            if (heading.group(1) != null) {
                String id = heading.group(1);
                tier = Character.toUpperCase(id.charAt(0)) + id.substring(1);
            } else if (tier != null) {
                entries.add(resolve(tier, new Raw(unescape(heading.group(2)).strip(), null, null), guide.games(), guide.versions()));
            }
        }
        return entries;
    }

    private static String authors(String html, String source) {
        String credits = unescape(match(AUTHOR, html, source).replaceAll("<[^>]*>", "")).strip();
        int sentenceEnd = credits.indexOf(". ");
        String writers = sentenceEnd < 0 ? credits : credits.substring(0, sentenceEnd);
        return writers.endsWith(".") ? writers.substring(0, writers.length() - 1) : writers;
    }

    // ---- entries ----

    /**
     * Resolves an entry. A note in parentheses either restricts it to some versions ("RB", "Ruby only"), says nothing
     * about the evolution line ("Both", "50% Form") or names the evolution path it is rated for ("Trade",
     * "Vaporeon"); the path's last stage then comes from the page's anchor id or the transcription.
     */
    private Entry resolve(String tier, Raw raw, List<String> listGames, Map<String, String> versions) {
        Matcher note = NOTE.matcher(raw.heading());
        String base = note.matches() ? note.group(1) : raw.heading();
        int species = dex(raw.species() != null ? raw.species() : base, raw.heading());
        if (!note.matches() || PLAIN_NOTES.contains(note.group(2).toLowerCase(Locale.ROOT))) {
            return new Entry(tier, raw.heading(), species, null, null);
        }
        List<String> games = versionGames(note.group(2), versions);
        if (games != null) {
            return new Entry(tier, raw.heading(), species, null, games.containsAll(listGames) ? null : games);
        }
        if (raw.target() == null) {
            throw new IllegalStateException("No evolution target for " + raw.heading());
        }
        int upTo = dex(raw.target(), raw.heading());
        if (!isAncestorOrEqual(species, upTo)) {
            throw new IllegalStateException(raw.heading() + ": " + raw.target() + " is not an evolution of " + base);
        }
        return new Entry(tier, raw.heading(), species, upTo, null);
    }

    /** The games a version note means, or null if it is no version note. */
    private static List<String> versionGames(String note, Map<String, String> versions) {
        List<String> games = new ArrayList<>();
        for (String word : note.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (versions.containsKey(word)) {
                if (!games.contains(versions.get(word))) {
                    games.add(versions.get(word));
                }
            } else if (!VERSION_FILLER.contains(word) && !word.isEmpty()) {
                return null;
            }
        }
        return games.isEmpty() ? null : games;
    }

    private boolean isAncestorOrEqual(int ancestor, int species) {
        for (Integer current = species; current != null; current = evolvesFrom.get(current)) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private int dex(String name, String heading) {
        Integer dex = dexByName.get(key(name));
        if (dex == null) {
            throw new IllegalStateException("Unknown Pokémon " + name + " in entry " + heading);
        }
        return dex;
    }

    /** Letters and digits only: "Mr. Mime", "mr-mime" and "mrmime" are the same name; Nidoran♀ is nidoran-f. */
    private static String key(String name) {
        String plain = Normalizer.normalize(name.replace("♀", "f").replace("♂", "m"), Normalizer.Form.NFD);
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void requireStarred(String id, List<Entry> entries, List<String> starred) {
        for (String tier : starred) {
            if (entries.stream().noneMatch(entry -> entry.tier().equals(tier))) {
                throw new IllegalStateException(id + ": no entries in tier " + tier + " - page layout changed?");
            }
        }
    }

    // ---- output ----

    private static String tierYaml(String name, String author, String url, int lastDex, List<String> starred,
                                   List<Entry> entries, String header) {
        StringBuilder yaml = new StringBuilder("# " + header + "\n" + """
                # lastDex: the highest national dex number of the game; Pokémon after it (newer games) are not rated.
                # starred: the tiers that get the catch star.
                # Entries: species = the Pokémon the entry names; it covers its earlier stages and its later ones, or with
                # upTo only the evolutions up to that stage (Abra (Trade): to Alakazam). games: only in these games.
                """);
        yaml.append("name: ").append(quote(name)).append('\n')
                .append("author: ").append(quote(author)).append('\n')
                .append("url: ").append(quote(url)).append('\n')
                .append("lastDex: ").append(lastDex).append('\n')
                .append("starred: [").append(String.join(", ", starred)).append("]\n")
                .append("entries:\n");
        for (Entry entry : entries) {
            yaml.append("  - { tier: ").append(entry.tier()).append(", name: ").append(quote(entry.name()))
                    .append(", species: ").append(entry.species());
            if (entry.upTo() != null) {
                yaml.append(", upTo: ").append(entry.upTo());
            }
            if (entry.games() != null) {
                yaml.append(", games: [").append(String.join(", ", entry.games())).append(']');
            }
            yaml.append(" }\n");
        }
        return yaml.toString();
    }

    private static Raw entry(String heading) {
        return new Raw(heading, null, null);
    }

    private static Raw entry(String heading, String target) {
        return new Raw(heading, target, null);
    }

    private static List<Raw> names(String... headings) {
        return Arrays.stream(headings).map(GenerateCatchData::entry).toList();
    }

    private static Map<String, List<Raw>> tiers(List<Raw> s, List<Raw> a) {
        Map<String, List<Raw>> tiers = new LinkedHashMap<>();
        tiers.put("S", s);
        tiers.put("A", a);
        return tiers;
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String unescape(String html) {
        return html.replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&eacute;", "é")
                .replace("&amp;", "&");
    }

    private static String match(Pattern pattern, String text, String source) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("No " + pattern + " in " + source);
        }
        return matcher.group(1).strip();
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
