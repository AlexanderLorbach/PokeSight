package de.alorbach.pokesight.update;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckTest {

    @Test
    void readsVersionAndPageOfTheLatestRelease() {
        String json = """
                {"tag_name": "v1.3.0", "name": "PokeSight 1.3.0",
                 "html_url": "https://github.com/AlexanderLorbach/PokeSight/releases/tag/v1.3.0"}""";

        assertThat(UpdateCheck.parse(json)).contains(new UpdateCheck.Release("1.3.0",
                URI.create("https://github.com/AlexanderLorbach/PokeSight/releases/tag/v1.3.0")));
    }

    @Test
    void ignoresAnswersItDoesNotUnderstand() {
        assertThat(UpdateCheck.parse("{\"message\": \"API rate limit exceeded\"}")).isEmpty();
        assertThat(UpdateCheck.parse("not json")).isEmpty();
        assertThat(UpdateCheck.parse("{\"tag_name\": \"v9.9.9\", \"html_url\": \"https://example.com/\"}")).isEmpty();
    }

    @Test
    void comparesVersionsNumerically() {
        assertThat(UpdateCheck.isNewer("1.10.0", "1.9.3")).isTrue();
        assertThat(UpdateCheck.isNewer("1.2.1", "1.2.0")).isTrue();
        assertThat(UpdateCheck.isNewer("1.2.1", "1.2.1")).isFalse();
        assertThat(UpdateCheck.isNewer("1.2.0", "1.3.0")).isFalse();
        assertThat(UpdateCheck.isNewer("2.0.0", "dev")).isFalse();
    }

    @Test
    void knowsItsOwnVersionFromTheBuild() {
        assertThat(UpdateCheck.currentVersion()).hasValueSatisfying(version -> assertThat(version).matches("\\d+\\.\\d+\\.\\d+"));
    }
}
