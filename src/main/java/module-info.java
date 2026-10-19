module de.alorbach.pokesight {
    requires java.desktop;
    requires java.net.http;
    requires java.prefs;
    requires javafx.controls;
    requires tools.jackson.databind;
    requires tools.jackson.dataformat.yaml;
    requires org.bytedeco.tesseract;

    // JavaCPP loads the native libraries from these modules; nothing else pulls them into the module graph.
    requires org.bytedeco.javacpp.windows.x86_64;
    requires org.bytedeco.leptonica.windows.x86_64;
    requires org.bytedeco.tesseract.windows.x86_64;
    requires org.bytedeco.javacpp.linux.x86_64;
    requires org.bytedeco.leptonica.linux.x86_64;
    requires org.bytedeco.tesseract.linux.x86_64;

    // Jackson maps the YAML files onto records in these packages.
    opens de.alorbach.pokesight.game to tools.jackson.databind;
    opens de.alorbach.pokesight.ruleset to tools.jackson.databind;
    opens de.alorbach.pokesight.settings to tools.jackson.databind;
    opens de.alorbach.pokesight.capture to tools.jackson.databind;

    exports de.alorbach.pokesight to javafx.graphics;
}
