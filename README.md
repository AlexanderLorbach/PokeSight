# PokeSight

> **About this project and AI:** PokeSight was coded entirely by an AI coding assistant (Claude Code); I directed it,
> tested the app and decided what to build. I did this on purpose, to see how a fully AI-coded app compares with an
> app I develop myself from scratch. **Result:** for a simple app like this it works surprisingly well, and it was
> surprisingly fast.

PokeSight is a second-screen companion for Pokémon games. It reads the opponents' names from the screen, detects
when a battle starts and shows each opponent's weaknesses, resistances and immunities, plus which moves of your team
hit it hardest. It only looks at the screen, so it works with any emulator.

- Single and double battles, auto-detection plus **Scan now** if a battle was missed
- Correct a misread Pokémon or add one by hand with a name search
- **Your best moves**: enter your team once, see the strongest move per opponent
- **Borderless mode**: only the cards, filling a small window you can put over a part of the emulator
- Dark theme, original sprites, English and German game names

**Supported today:** Pokémon Platinum (English and German) with the Gen 4 rules, on Windows.

> PokeSight is a fan project. It is not affiliated with, endorsed or sponsored by Nintendo, Game Freak, Creatures or
> The Pokémon Company. Pokémon and all related names are trademarks of their respective owners.

## Getting started

### Option 1: Download a release (no Java needed)

1. Download `PokeSight-<version>-windows.zip` from the [Releases](../../releases) page.
2. Unzip it anywhere and start `PokeSight\PokeSight.exe`.

The first battle view downloads the Pokémon sprites (under 1 MB) from [PokeAPI/sprites](https://github.com/PokeAPI/sprites);
until then, or offline, the cards show no sprite.

### Option 2: Run from source

Requirements: Windows and a JDK 25 (for example `winget install EclipseAdoptium.Temurin.25.JDK`). Clone the repository
to a Windows folder (not inside WSL: the app captures the Windows desktop), then:

```
gradlew.bat run
```

or run the Gradle task `run` (group *application*).

The first build downloads the OCR models and the test screenshots (see [Licenses](#licenses-and-credits)).

### Option 3: Build your own exe

| Gradle task (group *distribution*) | Result |
|---|---|
| `gradlew.bat installLocal` | Installs for the current user to `%LOCALAPPDATA%\Programs\PokeSight` with desktop and Start menu shortcuts |
| `gradlew.bat portableZip` | `build/distributions/PokeSight-<version>-windows.zip`: unzip anywhere and start `PokeSight.exe` |
| `gradlew.bat jpackageImage` | Just the app folder `build/jpackage/PokeSight` (`PokeSight.exe` + Java runtime) |

## Using PokeSight

1. Start the game in your emulator and PokeSight next to it (a second monitor works best).
2. On the start screen pick the game and its language.
3. Optionally select the **screen areas** where the opponents' name boxes appear:

   | Setting | What to select |
   |---|---|
   | Single battle area | the opponent's name box in a single battle |
   | Double battle area | the part with both opponents' name boxes in a double battle |

   Each scan reads every selected area and uses the one that recognizes the most names. Without any area the whole
   main screen is read: slower, and it may pick up other windows. PokeSight reads what is visible on screen, including
   its own window, so never let it cover a selected area. With melonDS's hybrid layout, select the name boxes on the
   large screen; PokeSight can cover the small copy of the top screen.
4. Press **Start**. Use **Capture preview** to check what the scan sees.

| Control | Purpose |
|---|---|
| automatic | A battle starts when the same names are read in two scans in a row (every 0.7 s); it ends after about 4 s without any name |
| **Scan now** | Reads the screen immediately, even if auto-detection did not trigger |
| **Team** | Your team: up to six Pokémon with up to four moves each |
| **Change** (on a card) | Search for the correct Pokémon if a name was misread; also for alternate forms like Sandy Wormadam |
| **Add Pokémon…** | Add an opponent by hand |
| **Clear** | Forget the current battle, corrections and additions |
| **Capture preview** | Shows (and saves) the last captured images |
| **Always on top** | Keeps the window above the emulator |
| **Borderless** | Only the cards remain and fill the window: one opponent the whole window, two opponents one half each. Hovering shows a button bar (scan, clear, team, always on top, exit); Esc also exits; drag to move |

**Your best moves** ranks the team's moves per opponent by move power × type effectiveness × 1.5 if the move has its
Pokémon's type (STAB). Stats, level and abilities are not taken into account: it shows which move hits hardest by type
and power, not the exact damage.

## Settings

All settings are plain YAML files in `%APPDATA%\PokeSight`; nothing is written to the registry. Edit them only while
PokeSight is closed; a file that cannot be read is ignored and the defaults are used. Delete the folder to reset.
The sprites are a download cache in `%LOCALAPPDATA%\PokeSight\sprites`; they are downloaded again if deleted.

```
%APPDATA%\PokeSight\
├─ settings.yaml          last game and language, window position, size and maximized state
└─ games\
   └─ <game id>.yaml      name-box areas (singleArea, doubleArea) and your team for that game
```

```yaml
# games/platinum.yaml
singleArea: { x: 2, "y": 210, width: 757, height: 211 }   # screen pixels; null = not selected
doubleArea: { x: 1, "y": 104, width: 1099, height: 418 }
team:
  - { pokemon: 390, form: 0, moves: [52, 10] }            # national dex number, form index, move ids
```

## How it works

Every 0.7 seconds PokeSight takes a screenshot of the selected areas and reads them with [Tesseract](https://github.com/tesseract-ocr/tesseract) OCR.
Game fonts are tiny pixel fonts with a drop shadow, so each image is prepared first: only the pixels close to the text
colour are kept (Platinum has white text on dark boxes, Diamond/Pearl dark text on light ones, so both are tried),
everything that is not letter-sized (box borders, HP bars) is removed and the letters are scaled to about twice the DS
size. The text is then matched against the game's Pokémon names with a small edit distance, and in games that write
names in capitals, text in mixed case (code, chats, websites) is ignored. The OCR is tested on real Platinum and
Diamond/Pearl screenshots at emulator sizes 1×–4×, sharp and smoothed.

## Development

```
src/main/java/de/alorbach/pokesight/
├─ PokeSightApp.java     entry point, wires everything together
├─ game/                 game definitions (games/*.yaml): ruleset, languages, sprite URL
├─ sprite/               sprite download and cache in %LOCALAPPDATA%\PokeSight\sprites
├─ ruleset/              generation data: types, type chart, species, forms, moves
├─ capture/              screenshots of screen regions
├─ ocr/                  Tesseract adapter and image preparation
├─ battle/               name matching, battle scanning, auto-detection
├─ matchup/              weaknesses, team and move ranking
├─ settings/             settings files in %APPDATA%\PokeSight
└─ ui/                   JavaFX views (start screen, battle view, cards, team editor, title bar, theme)

src/main/resources/      games/*.yaml, rulesets/gen4/*.yaml (generated), ui/ (theme, font, icons)
packaging/               PokeSight.ico, install.ps1 (installLocal)
tools/                   GenerateRuleset.java (PokeAPI → YAML), GenerateIcon.java (app icon)
.github/workflows/       release.yml: builds the portable zip for every pushed v* tag
licenses/                license texts of the bundled third-party software (THIRD-PARTY-NOTICES.md)
```

| Pattern | Where | Why |
|---|---|---|
| Adapter | `TextRecognizer` / `TesseractTextRecognizer` | Swap the OCR engine without touching the battle logic |
| Adapter | `ScreenSource` / `RobotScreenSource` | Screenshots are faked in tests; another source can plug in later |
| Observer | `BattleMonitor` → `BattleView` | Background scanning pushes changes to the UI |
| View model | `BattleBoard` | Corrections and added Pokémon, testable without JavaFX |

- **Tests:** `gradlew.bat test`.
- **Release:** push an annotated tag such as `v1.0.0`; the workflow tests, builds the portable zip and attaches it to
  a release titled with the tag message's first line.
- **Add a game:** create `games/<id>.yaml` (copy `platinum.yaml`) and list `<id>` in `games/index.yaml`.
- **Regenerate a ruleset:** `java tools/GenerateRuleset.java 4 src/main/resources/rulesets/gen4`. It writes the type
  chart, the species with their alternate forms and the damaging moves, with types and move power as they were in that
  generation (PokeAPI's change log is rolled back, e.g. Thunderbolt has 95 power in Gen 4).
- **Data checked** against Pokémon Showdown's Gen 4 data: type chart, types of all 493 species and power, type and
  category of all moves.

## Known limitations

- Windows only (screen capture of the Windows desktop).
- Levels are rarely read; names are.
- The battle screen only shows the species name, so alternate forms (Wormadam cloaks, Shaymin Sky, Arceus plates) are
  shown as the base form; use **Change** to pick the form.
- Type changes during battle (Castform's Forecast, Kecleon's Color Change) are not tracked.
- With display scaling, monitors with different scale factors can shift the selected areas.

## Licenses and credits

PokeSight's own code is released under the [MIT License](LICENSE): free to use, change and fork. The app bundles
third-party software under its own licenses: see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the
[licenses](licenses) folder, both also included in the app folder.

Neither the repository nor the release contains Pokémon images: the app downloads the sprites on first start, the
build downloads the test screenshots. They are not covered by the MIT License:

| What | Source | License / owner |
|---|---|---|
| Sprites | [PokeAPI/sprites](https://github.com/PokeAPI/sprites), pinned commit (URL in `games/platinum.yaml`) | © Nintendo / Game Freak / The Pokémon Company |
| Test screenshots | Bulbagarden Archives, Wikipedia, PokéWiki (URLs in `build.gradle.kts`) | © Nintendo / Game Freak / The Pokémon Company |
| Pokémon data (names, types, moves) | [PokeAPI](https://pokeapi.co), cross-checked with [Pokémon Showdown](https://github.com/smogon/pokemon-showdown) | game data © Nintendo / Game Freak / The Pokémon Company |
| OCR engine | [Tesseract](https://github.com/tesseract-ocr/tesseract) via [JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) | Apache 2.0 |
| OCR models | [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) 4.1.0 | Apache 2.0 |
| UI | [OpenJFX](https://openjfx.io) | GPL 2.0 with Classpath Exception |
| YAML | [Jackson](https://github.com/FasterXML/jackson) | Apache 2.0 |
| Display font | [Fredoka](https://fonts.google.com/specimen/Fredoka) (bundled, `src/main/resources/ui/fonts`) | SIL Open Font License 1.1 |
| Type colours | Scarlet/Violet palette from [WikiDex](https://www.wikidex.net/wiki/Ayuda:Paleta_de_colores) | colour values |
| Gen 4 Arceus form order | [PKHeX](https://github.com/kwsch/PKHeX) `FormItem.GetFormArceusPlate` | reference |
