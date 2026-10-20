import java.net.URI
import java.security.MessageDigest

plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "4.1.1"
}

group = "de.alorbach"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "25.0.4"
    modules = listOf("javafx.controls")
}

val tesseractVersion = "5.5.3-1.5.14"
val leptonicaVersion = "1.87.0-1.5.14"
val javacppVersion = "1.5.14"
// Native libraries only for the platforms we run on (Windows) and test on (Linux/WSL).
val nativePlatforms = listOf("windows-x86_64", "linux-x86_64")

dependencies {
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.2.4")
    implementation("org.bytedeco:tesseract:$tesseractVersion")
    // module-info requires the native modules (nothing else pulls them into the module graph), so they must be on the compile path.
    nativePlatforms.forEach { platform ->
        implementation("org.bytedeco:tesseract:$tesseractVersion:$platform")
        implementation("org.bytedeco:leptonica:$leptonicaVersion:$platform")
        implementation("org.bytedeco:javacpp:$javacppVersion:$platform")
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------------------------------------------------
// Downloaded at build time instead of kept in the repository: the OCR models, and the test screenshots, which are
// © Nintendo / Game Freak / The Pokémon Company and must not be redistributed with the code. The app downloads the
// Pokémon sprites itself on first start (sprite.SpriteCache), so the release does not contain them either.
// ---------------------------------------------------------------------------------------------------------------------

fun download(url: String): ByteArray {
    val connection = URI.create(url).toURL().openConnection()
    connection.setRequestProperty("User-Agent", "PokeSight-build (https://github.com/AlexanderLorbach/PokeSight)")
    return connection.getInputStream().use { it.readBytes() }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// Tesseract language models (tessdata_fast), pinned by tag and checksum, bundled as resources under /tessdata.
val tessdataModels = mapOf(
    "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    "deu" to "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
)
val downloadTessdata = tasks.register("downloadTessdata") {
    val outputDir = layout.buildDirectory.dir("generated/tessdata")
    inputs.property("models", tessdataModels)
    outputs.dir(outputDir)
    doLast {
        tessdataModels.forEach { (language, expected) ->
            val target = outputDir.get().file("tessdata/$language.traineddata").asFile
            target.parentFile.mkdirs()
            val bytes = download("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/$language.traineddata")
            val actual = sha256(bytes)
            if (actual != expected) {
                throw GradleException("Checksum mismatch for $language.traineddata: expected $expected, got $actual")
            }
            target.writeBytes(bytes)
        }
    }
}

sourceSets.main {
    resources.srcDir(downloadTessdata)
}

// Real battle screenshots for the OCR tests (fan-wiki hosted, pinned by checksum), under /screenshots in the tests.
// Some wikis block downloads from CI servers (Bulbagarden answers GitHub Actions with HTTP 403): a screenshot that cannot
// be downloaded is skipped with a warning and its tests are skipped. A wrong checksum still fails the build.
val testScreenshots: Map<String, Pair<String, String>> = file("src/test/resources/screenshots.tsv").readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { it.split('\t') }
    .associate { it[0] to (it[1] to it[2]) }
val downloadTestScreenshots = tasks.register("downloadTestScreenshots") {
    val outputDir = layout.buildDirectory.dir("generated/test-screenshots")
    val cacheDir = gradle.gradleUserHomeDir.resolve("caches/pokesight/test-screenshots")
    inputs.property("screenshots", testScreenshots.toString())
    outputs.dir(outputDir)
    // Retry skipped downloads on the next build instead of treating the incomplete folder as up to date.
    outputs.upToDateWhen { testScreenshots.keys.all { outputDir.get().file("screenshots/$it").asFile.exists() } }
    doLast {
        val target = outputDir.get().dir("screenshots").asFile
        target.mkdirs()
        cacheDir.mkdirs()
        testScreenshots.forEach { (name, source) ->
            val (url, expected) = source
            val cached = cacheDir.resolve(name)
            if (!cached.exists() || sha256(cached.readBytes()) != expected) {
                val bytes = try {
                    download(url)
                } catch (e: java.io.IOException) {
                    logger.warn("Test screenshot $name not downloaded, its OCR tests are skipped: ${e.message}")
                    return@forEach
                }
                val actual = sha256(bytes)
                if (actual != expected) {
                    throw GradleException("Checksum mismatch for test screenshot $name from $url: expected $expected, got $actual")
                }
                cached.writeBytes(bytes)
            }
            cached.copyTo(target.resolve(name), overwrite = true)
        }
    }
}
sourceSets.test {
    resources.srcDir(downloadTestScreenshots)
}

val jvmFlags = listOf(
    "--enable-native-access=javafx.graphics,org.bytedeco.javacpp,org.bytedeco.leptonica,org.bytedeco.tesseract",
    // JavaFX 25 preview API (StageStyle.EXTENDED, HeaderBar): the app draws its own dark title bar.
    "-Djavafx.enablePreview=true",
    "-Djavafx.suppressPreviewWarning=true",
)

application {
    mainModule = "de.alorbach.pokesight"
    mainClass = "de.alorbach.pokesight.PokeSightApp"
    applicationDefaultJvmArgs = jvmFlags
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djavafx.enablePreview=true", "-Djavafx.suppressPreviewWarning=true") // tests run on the class path
}

jlink {
    options = listOf("--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6")
    launcher {
        name = "PokeSight"
        jvmArgs = jvmFlags
    }
    jpackage {
        icon = "packaging/PokeSight.ico" // generated by tools/GenerateIcon.java; used for the exe and the shortcuts
        // Only the app folder (PokeSight.exe + runtime): no installer, so no WiX toolset needed.
        skipInstaller = true
    }
}

val appImage = layout.buildDirectory.dir("jpackage/PokeSight")

// The app folder ships with PokeSight's license and the licenses of the bundled third-party software.
tasks.named("jpackageImage") {
    val legal = listOf(file("LICENSE"), file("THIRD-PARTY-NOTICES.md"), file("licenses"))
    val target = appImage
    inputs.files(legal)
    doLast {
        legal.forEach { it.copyRecursively(target.get().asFile.resolve(it.name), overwrite = true) }
    }
}

// Portable app: zip of the app folder; unzip anywhere and start PokeSight.exe.
val portableZip = tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Zips the app folder (PokeSight.exe + runtime) into build/distributions."
    dependsOn("jpackageImage")
    from(appImage) { into("PokeSight") }
    archiveFileName = "PokeSight-${project.version}-windows.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

// Installs for the current Windows user without an installer: copies the app folder to
// %LOCALAPPDATA%\Programs\PokeSight and creates desktop and Start menu shortcuts.
val installLocal = tasks.register("installLocal") {
    group = "distribution"
    description = "Installs PokeSight for the current Windows user with desktop and Start menu shortcuts."
    dependsOn("jpackageImage")
    onlyIf("only on Windows") { System.getProperty("os.name").startsWith("Windows") }
    val source = appImage
    val script = layout.projectDirectory.file("packaging/install.ps1")
    doLast {
        val process = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", script.asFile.absolutePath, "-Source", source.get().asFile.absolutePath)
            .inheritIO().start()
        if (process.waitFor() != 0) {
            throw GradleException("packaging/install.ps1 failed with exit code ${process.exitValue()}")
        }
    }
}
