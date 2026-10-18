package io.github.olegnyr.toolslog.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringStructuredLogEncoderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Имя и версия — из spring.application.*, коммит — из logging.structured.ecs.service")
    void encode_shouldTakeServiceFieldsFromEnvironment_whenEnvironmentInContext() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "orders")
                .withProperty("spring.application.version", "1.4.0")
                .withProperty(SpringStructuredLogEncoder.SERVICE_PREFIX + ".commit", "616e59d")
                .withProperty("spring.application.pid", "42");

        JsonNode json = encode(environment, new SpringStructuredLogEncoder(), null);

        assertThat(json.get("servicesc").get("name").asString()).isEqualTo("orders");
        assertThat(json.get("servicesc").get("version").asString()).isEqualTo("1.4.0");
        assertThat(json.get("servicesc").get("commit").asString()).isEqualTo("616e59d");
        assertThat(json.get("process").get("pid").asLong()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Свойство encoder'а главнее настроек Environment")
    void encode_shouldPreferEncoderProperty_whenBothSet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SpringStructuredLogEncoder.SERVICE_PREFIX + ".name", "from-ecs")
                .withProperty("spring.application.name", "from-app");
        SpringStructuredLogEncoder encoder = new SpringStructuredLogEncoder();
        encoder.setName("from-encoder");

        JsonNode json = encode(environment, encoder, null);

        assertThat(json.get("servicesc").get("name").asString()).isEqualTo("from-encoder");
    }

    @Test
    @DisplayName("Стектрейс печатается принтером Spring Boot")
    void encode_shouldPrintStackTrace_whenEventHasThrowable() {
        JsonNode json = encode(new MockEnvironment(), new SpringStructuredLogEncoder(),
                new IllegalStateException("boom"));

        assertThat(json.get("error").get("type").asString()).isEqualTo(IllegalStateException.class.getName());
        assertThat(json.get("error").get("stack_trace").asString())
                .startsWith("java.lang.IllegalStateException: boom");
    }

    private static JsonNode encode(MockEnvironment environment, SpringStructuredLogEncoder encoder,
                                   Throwable throwable) {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        encoder.setContext(context);
        encoder.start();
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("logger"), Level.INFO, "msg", throwable, null);
        event.setMDCPropertyMap(Map.of());
        return MAPPER.readTree(encoder.encode(event));
    }
}
