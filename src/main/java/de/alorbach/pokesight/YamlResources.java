package de.alorbach.pokesight;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongFunction;

/** Reads the bundled YAML data files (games, rulesets) from the classpath. */
public final class YamlResources {

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // catch typos in hand-edited files
            .addModule(new SimpleModule("hex-numbers")
                    .addDeserializer(Long.class, new HexNumberDeserializer<>(Long.class, value -> value))
                    .addDeserializer(long.class, new HexNumberDeserializer<>(Long.class, value -> value))
                    .addDeserializer(Integer.class, new HexNumberDeserializer<>(Integer.class, Math::toIntExact))
                    .addDeserializer(int.class, new HexNumberDeserializer<>(Integer.class, Math::toIntExact)))
            .build();

    private YamlResources() {
    }

    public static <T> T read(String path, Class<T> type) {
        try (InputStream in = YamlResources.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return MAPPER.readValue(in, type);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Invalid resource " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Memory addresses read best in hex, but the YAML parser hands {@code 0x021C0794} and
     * {@code -0x2D5C} over as strings. Accepts decimal and (signed) hex.
     */
    private static final class HexNumberDeserializer<T extends Number> extends ValueDeserializer<T> {

        private final Class<T> type;
        private final LongFunction<T> converter;

        HexNumberDeserializer(Class<T> type, LongFunction<T> converter) {
            this.type = type;
            this.converter = converter;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return converter.apply(parser.getLongValue());
            }
            String text = parser.getValueAsString();
            try {
                if (text != null) {
                    return converter.apply(Long.decode(text.strip()));
                }
            } catch (NumberFormatException | ArithmeticException e) {
                // fall through to Jackson's error reporting
            }
            return type.cast(context.handleWeirdStringValue(type, text, "expected a decimal or 0x-prefixed hex number"));
        }
    }
}
