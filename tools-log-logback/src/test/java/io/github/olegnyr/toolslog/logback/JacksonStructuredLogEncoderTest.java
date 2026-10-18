package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JacksonStructuredLogEncoderTest {

    @Test
    @DisplayName("Encoder стартует без настроек и без Spring")
    void start_shouldNotThrow_whenNothingConfigured() {
        JacksonStructuredLogEncoder encoder = new JacksonStructuredLogEncoder();
        encoder.setContext(new LoggerContext());

        assertThatCode(encoder::start).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Свойства encoder'а попадают в поля сервиса, pid — текущего процесса")
    void encode_shouldWriteConfiguredServiceFieldsAndPid_whenPropertiesSet() {
        LoggerContext context = new LoggerContext();
        JacksonStructuredLogEncoder encoder = new JacksonStructuredLogEncoder();
        encoder.setContext(context);
        encoder.setName("orders");
        encoder.setSystem("shop");
        encoder.start();
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("logger"), Level.INFO, "msg", null, null);
        event.setMDCPropertyMap(Map.of());

        JsonNode json = JsonMapper.builder().build().readTree(encoder.encode(event));

        assertThat(json.get("system").asString()).isEqualTo("shop");
        assertThat(json.get("servicesc").get("name").asString()).isEqualTo("orders");
        assertThat(json.get("process").get("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
    }
}
