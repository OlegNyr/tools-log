package io.github.olegnyr.toolslog.logback;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты на нетривиальные методы {@link SimpleJsonGenerator} — pattern-switch
 * в {@code writeMap} и null/blank-фильтрация в {@code writeNullable}.
 */
class SimpleJsonGeneratorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void writeMap_shouldEmitStringFields() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Alice");
        input.put("city", "Moscow");

        JsonNode out = roundTrip(g -> g.writeMap(input));

        assertThat(out.get("name").asString()).isEqualTo("Alice");
        assertThat(out.get("city").asString()).isEqualTo("Moscow");
    }

    @Test
    void writeMap_shouldNestMapValuesRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("method", "GET");
        inner.put("status", "200");
        Map<String, Object> input = Map.of("http", inner);

        JsonNode out = roundTrip(g -> g.writeMap(input));

        assertThat(out.get("http").get("method").asString()).isEqualTo("GET");
        assertThat(out.get("http").get("status").asString()).isEqualTo("200");
    }

    @Test
    void writeMap_shouldEmitJsonNull_forNullValue() {
        Map<String, Object> input = new HashMap<>();
        input.put("absent", null);

        JsonNode out = roundTrip(g -> g.writeMap(input));

        assertThat(out.get("absent").isNull()).isTrue();
    }

    @Test
    void writeMap_shouldFallbackToString_forNumberAndBoolean() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", 42);
        input.put("flag", true);

        JsonNode out = roundTrip(g -> g.writeMap(input));

        assertThat(out.get("count").asString()).isEqualTo("42");
        assertThat(out.get("flag").asString()).isEqualTo("true");
    }

    @Test
    void writeNullable_shouldSkipNullValue() {
        JsonNode out = roundTrip(g -> g.writeNullable("k", null));

        assertThat(out.has("k")).isFalse();
    }

    @Test
    void writeNullable_shouldSkipBlankString() {
        JsonNode out = roundTrip(g -> g.writeNullable("k", "   "));

        assertThat(out.has("k")).isFalse();
    }

    @Test
    void writeNullable_shouldEmitNonBlankString() {
        JsonNode out = roundTrip(g -> g.writeNullable("k", "value"));

        assertThat(out.get("k").asString()).isEqualTo("value");
    }

    @Test
    void writeMap_shouldHandleDeeplyNestedMaps() {
        Map<String, Object> level3 = Map.of("c", "x");
        Map<String, Object> level2 = Map.of("b", level3);
        Map<String, Object> input = Map.of("a", level2);

        JsonNode out = roundTrip(g -> g.writeMap(input));

        assertThat(out.get("a").get("b").get("c").asString()).isEqualTo("x");
    }

    // ---- Helpers ----

    private interface Body {
        void write(SimpleJsonGenerator gen) throws Exception;
    }

    private static JsonNode roundTrip(Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonFactory jf = new JsonFactory();
        try (JsonGenerator gen = jf.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            SimpleJsonGenerator s = new SimpleJsonGenerator(gen);
            s.writeStartObject();
            body.write(s);
            s.writeEndObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return MAPPER.readTree(out.toByteArray());
    }
}
