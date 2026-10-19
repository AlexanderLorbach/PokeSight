# Third-party notices

PokeSight's own code is under the MIT License (`LICENSE`). The PokeSight app (`PokeSight.exe` with its Java runtime)
also contains the following third-party software. Their license texts are in the `licenses` folder.

## Java libraries

| Component | Version | License | Source |
|---|---|---|---|
| OpenJFX (javafx-base, -graphics, -controls) | 25.0.4 | GPL 2.0 with Classpath Exception (`GPL-2.0-with-Classpath-Exception.txt`) | https://github.com/openjdk/jfx |
| JavaCPP, JavaCPP Presets for Tesseract and Leptonica | 1.5.14 | Apache 2.0 (`Apache-2.0.txt`) | https://github.com/bytedeco/javacpp-presets |
| Jackson (core, databind, dataformat-yaml, annotations) | 3.2.4 / 2.22 | Apache 2.0 (`Apache-2.0.txt`) | https://github.com/FasterXML/jackson |
| SnakeYAML Engine | 3.0.1 | Apache 2.0 (`Apache-2.0.txt`) | https://bitbucket.org/snakeyaml/snakeyaml-engine |
| Java runtime (OpenJDK modules) | 25 | GPL 2.0 with Classpath Exception, see `runtime/legal` | https://openjdk.org |

## Native libraries (bundled by the JavaCPP Presets)

| Component | Version | License |
|---|---|---|
| Tesseract OCR | 5.5.3 | Apache 2.0 (`Apache-2.0.txt`) |
| Leptonica | 1.87.0 | BSD 2-clause (`Leptonica.txt`) |
| zlib | 1.3.2 | zlib (`zlib.txt`) |
| libpng | 1.6.53 | PNG Reference Library License v2 (`libpng.txt`) |
| libjpeg-turbo | 3.0.1 | IJG, BSD 3-clause, zlib (`libjpeg-turbo.txt`, `libjpeg-turbo-IJG.txt` = upstream `README.ijg`). This software is based in part on the work of the Independent JPEG Group. |
| libtiff | 4.7.1 | libtiff (`libtiff.txt`) |
| libwebp | | BSD 3-clause plus patent grant (`libwebp.txt`, `libwebp-PATENTS.txt`) |
| OpenJPEG | | BSD 2-clause (`OpenJPEG.txt`) |
| giflib | | MIT (`giflib.txt`) |
| GCC runtime (libgcc_s, libstdc++, libgomp) | | GPL 3.0 with GCC Runtime Library Exception 3.1 (`GPL-3.0.txt`, `GCC-Runtime-Library-Exception-3.1.txt`) |
| mingw-w64 winpthreads | | MIT and BSD (`winpthreads.txt`) |
| Microsoft Visual C++ runtime | | Microsoft Visual C++ Redistributable license |

## Data and fonts

| Component | License | Source |
|---|---|---|
| Tesseract models (tessdata_fast eng, deu) 4.1.0 | Apache 2.0 (`Apache-2.0.txt`) | https://github.com/tesseract-ocr/tessdata_fast |
| Fredoka font | SIL Open Font License 1.1 (`Fredoka-OFL-1.1.txt`) | https://github.com/hafontia/Fredoka-One |
| Pokémon data (names, types, moves), generated from PokeAPI | game data © Nintendo / Game Freak / The Pokémon Company | https://github.com/PokeAPI/pokeapi |

The Pokémon sprites are not included: the app downloads them on first start from https://github.com/PokeAPI/sprites
(© Nintendo / Game Freak / The Pokémon Company, not covered by the MIT License).

Pokémon and all related names are trademarks of Nintendo, Game Freak, Creatures and The Pokémon Company. PokeSight is
a fan project and not affiliated with, endorsed or sponsored by them.
