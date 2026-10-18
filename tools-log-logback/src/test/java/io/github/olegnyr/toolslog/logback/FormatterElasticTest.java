package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты на ECS-формат вывода {@link FormatterElastic}. Используется реальный
 * {@link LoggingEvent} (а не моки) — проще выставить MDC/KV/markers.
 */
class FormatterElasticTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void formatAsBytes_shouldEmitTimestampLevelLoggerAndMessage() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "com.example.demo", "hello {}", "world");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("@timestamp").asString()).isNotBlank();
        assertThat(json.get("level").asString()).isEqualTo("INFO");
        assertThat(json.get("logger").asString()).isEqualTo("com.example.demo");
        assertThat(json.get("message").asString()).isEqualTo("hello world");
        assertThat(json.get("ecs").get("version").asString()).isEqualTo("8.11");
        assertThat(json.get("process").get("thread").get("name").asString()).isNotBlank();
    }

    @Test
    void formatAsBytes_shouldEndWithLineSeparator() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        byte[] out = formatter.formatAsBytes(event);

        String s = new String(out);
        assertThat(s).endsWith(System.lineSeparator());
    }

    @Test
    void formatAsBytes_shouldIncludeMdcEntriesAsFlatFields() {
        FormatterElastic formatter = newFormatterWithPid();
        Map<String, String> mdc = new HashMap<>();
        mdc.put("traceId", "abc-123");
        mdc.put("userId", "guest@example.com");
        LoggingEvent event = newEventWithMdc(Level.INFO, "logger", "msg", mdc);

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("traceId").asString()).isEqualTo("abc-123");
        assertThat(json.get("userId").asString()).isEqualTo("guest@example.com");
    }

    @Test
    void formatAsBytes_shouldNestKeyValuePairsByDots() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("http.method", "GET"));
        event.addKeyValuePair(new KeyValuePair("http.status", 200));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("http").get("method").asString()).isEqualTo("GET");
        assertThat(json.get("http").get("status").asString()).isEqualTo("200");
    }

    @Test
    void formatAsBytes_shouldEmitFlatKeyValuePair_whenKeyHasNoDot() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("operation", "request"));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("operation").asString()).isEqualTo("request");
    }

    @Test
    void formatAsBytes_shouldEmitErrorObject_whenThrowablePresent() {
        FormatterElastic formatter = newFormatterWithPid();
        IllegalStateException cause = new IllegalStateException("boom");
        LoggingEvent event = newEvent(Level.ERROR, "logger", "fail");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(cause));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("error").get("type").asString()).isEqualTo(IllegalStateException.class.getName());
        assertThat(json.get("error").get("message").asString()).isEqualTo("boom");
        assertThat(json.get("error").get("stack_trace").asString()).contains("boom");
    }

    @Test
    void formatAsBytes_shouldEmitMarkersAsTagsArray_dedupedAcrossReferences() {
        FormatterElastic formatter = newFormatterWithPid();
        org.slf4j.Marker parent = MarkerFactory.getDetachedMarker("PARENT");
        org.slf4j.Marker child = MarkerFactory.getDetachedMarker("CHILD");
        parent.add(child);
        parent.add(MarkerFactory.getDetachedMarker("CHILD")); // duplicate name → один тэг
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addMarker(parent);

        JsonNode json = parse(formatter.formatAsBytes(event));

        JsonNode tags = json.get("tags");
        assertThat(tags.isArray()).isTrue();
        List<String> names = new java.util.ArrayList<>();
        tags.forEach(n -> names.add(n.asString()));
        assertThat(names).containsExactlyInAnyOrder("PARENT", "CHILD");
    }

    @Test
    void formatAsBytes_shouldOmitServiceInfo_whenServiceInfoEmpty() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.has("service")).isFalse();
        assertThat(json.has("system")).isFalse();
        assertThat(json.has("servicesc")).isFalse();
    }

    @Test
    void formatAsBytes_shouldEmitServiceInfo_whenNameAndVersionSet() {
        FormatterElastic formatter = newFormatter(
                ServiceInfo.builder().name("orders-service").version("0.0.1").build(), 1L);
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("servicesc").get("name").asString()).isEqualTo("orders-service");
        assertThat(json.get("servicesc").get("version").asString()).isEqualTo("0.0.1");
    }

    @Test
    void formatAsBytes_shouldIncludePid_whenPidSet() {
        FormatterElastic formatter = newFormatter(ServiceInfo.NONE, 42L);
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("process").get("pid").asLong()).isEqualTo(42L);
    }

    @Test
    void formatAsBytes_shouldOmitPidField_whenPidAbsent() {
        // pid опционален — без него поле просто не сериализуется, вместо падения с NPE на unbox'инге.
        FormatterElastic formatter = newFormatter(ServiceInfo.NONE, null);
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.has("process")).isTrue();
        assertThat(json.get("process").has("pid")).isFalse();
        // прочие поля process всё ещё пишутся
        assertThat(json.get("process").get("thread").get("name").asString()).isNotBlank();
    }

    @Test
    void formatAsBytes_shouldHandleDeepNesting_threeLevels() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("a.b.c", "x"));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("a").get("b").get("c").asString()).isEqualTo("x");
    }

    @Test
    void formatAsBytes_shouldStringifyNonStringKeyValues() {
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("count", 42));
        event.addKeyValuePair(new KeyValuePair("flag", true));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("count").asString()).isEqualTo("42");
        assertThat(json.get("flag").asString()).isEqualTo("true");
    }

    @Test
    void formatAsBytes_shouldEmitMixedNestedAndFlatPairs_streamedByPrefix() {
        // Перемешанные flat + dotted с разными префиксами — проверяем корректную потоковую группировку.
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("header.Accept", "*/*"));
        event.addKeyValuePair(new KeyValuePair("operation", "request"));
        event.addKeyValuePair(new KeyValuePair("header.Host", "localhost"));

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("operation").asString()).isEqualTo("request");
        assertThat(json.get("header").get("Accept").asString()).isEqualTo("*/*");
        assertThat(json.get("header").get("Host").asString()).isEqualTo("localhost");
    }

    @Test
    void formatAsBytes_shouldEmitCommitInServicesc_whenCommitSet() {
        FormatterElastic formatter = newFormatter(ServiceInfo.builder().commit("616e59d").build(), 1L);
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");

        JsonNode json = parse(formatter.formatAsBytes(event));

        assertThat(json.get("servicesc").get("commit").asString()).isEqualTo("616e59d");
    }

    // ---- Log forging: CR/LF и кавычки во внешних значениях ----
    // Инвариант, на котором держатся вызовы log.*(...) во всём проекте: событие = ОДНА строка
    // JSON, все внешние значения (message, MDC, KV) пишутся через Jackson writeString/writeName.
    // Поэтому подставить в лог лишнюю строку или лишнее поле через \r\n в значении нельзя.

    @Test
    void formatAsBytes_shouldEmitSingleLine_whenMessageContainsCrLf() {
        // Arrange — классический log-forging payload: перевод строки + «своё» событие.
        FormatterElastic formatter = newFormatterWithPid();
        String forged = "start\r\n{\"level\":\"ERROR\",\"message\":\"forged\"}";
        LoggingEvent event = newEvent(Level.INFO, "logger", forged);

        // Act
        String out = new String(formatter.formatAsBytes(event), StandardCharsets.UTF_8);

        // Assert — переводов строки ровно столько, сколько в терминаторе события.
        assertThat(countLineFeeds(out))
                .isEqualTo(countLineFeeds(System.lineSeparator()));
    }

    @Test
    void formatAsBytes_shouldKeepForgedPayloadInsideMessage_whenMessageContainsCrLf() {
        // Arrange
        FormatterElastic formatter = newFormatterWithPid();
        String forged = "start\r\n{\"level\":\"ERROR\",\"message\":\"forged\"}";
        LoggingEvent event = newEvent(Level.INFO, "logger", forged);

        // Act
        JsonNode json = parse(formatter.formatAsBytes(event));

        // Assert — payload целиком остался значением message, level настоящий.
        assertThat(json.get("message").asString()).isEqualTo(forged);
        assertThat(json.get("level").asString()).isEqualTo("INFO");
    }

    @Test
    void formatAsBytes_shouldEscapeCrLfInMdcValue_whenMdcValueContainsCrLf() {
        // Arrange — MDC заполняется в том числе из клиентских заголовков (client.ip, user.id).
        FormatterElastic formatter = newFormatterWithPid();
        Map<String, String> mdc = new HashMap<>();
        mdc.put("client.ip", "1.2.3.4\r\n{\"level\":\"ERROR\"}");
        LoggingEvent event = newEventWithMdc(Level.INFO, "logger", "msg", mdc);

        // Act
        String out = new String(formatter.formatAsBytes(event), StandardCharsets.UTF_8);

        // Assert
        assertThat(countLineFeeds(out)).isEqualTo(countLineFeeds(System.lineSeparator()));
    }

    @Test
    void formatAsBytes_shouldEscapeCrLfInKeyValuePair_whenHeaderValueContainsCrLf() {
        // Arrange — KV-пары Logbook'а несут заголовки запроса, то есть чистый клиентский ввод.
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "msg");
        event.addKeyValuePair(new KeyValuePair("header.X-Evil", "v\r\n{\"level\":\"ERROR\"}"));

        // Act
        String out = new String(formatter.formatAsBytes(event), StandardCharsets.UTF_8);

        // Assert
        assertThat(countLineFeeds(out)).isEqualTo(countLineFeeds(System.lineSeparator()));
    }

    @Test
    void formatAsBytes_shouldNotInjectExtraField_whenMessageContainsQuotesAndBraces() {
        // Arrange — вторая половина log forging: закрыть строку кавычкой и дописать поле.
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.INFO, "logger", "x\",\"injected\":\"yes");

        // Act
        JsonNode json = parse(formatter.formatAsBytes(event));

        // Assert
        assertThat(json.has("injected")).isFalse();
    }

    @Test
    void formatAsBytes_shouldEscapeCrLfInErrorMessage_whenThrowableMessageContainsCrLf() {
        // Arrange — сообщение исключения тоже бывает производным от клиентского ввода.
        FormatterElastic formatter = newFormatterWithPid();
        LoggingEvent event = newEvent(Level.ERROR, "logger", "fail");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                new IllegalStateException("boom\r\n{\"level\":\"ERROR\"}")));

        // Act
        String out = new String(formatter.formatAsBytes(event), StandardCharsets.UTF_8);

        // Assert — вместе со stack_trace, который сам по себе многострочный.
        assertThat(countLineFeeds(out)).isEqualTo(countLineFeeds(System.lineSeparator()));
    }

    // ---- Helpers ----

    private static int countLineFeeds(String value) {
        return (int) value.chars().filter(ch -> ch == '\n').count();
    }

    private static FormatterElastic newFormatter(ServiceInfo serviceInfo, Long pid) {
        ThrowableProxyConverter converter = new ThrowableProxyConverter();
        converter.start();
        return new FormatterElastic(serviceInfo, pid, converter::convert);
    }

    /** Стандартный formatter без полей сервиса и с pid=1. */
    private static FormatterElastic newFormatterWithPid() {
        return newFormatter(ServiceInfo.NONE, 1L);
    }

    private static LoggingEvent newEvent(Level level, String loggerName, String message, Object... args) {
        return newEventWithMdc(level, loggerName, message, Map.of(), args);
    }

    /**
     * Создаёт LoggingEvent с заранее проставленным MDC. Без вызова setMDCPropertyMap
     * на свежем event'е Logback 1.5 пытается лениво поднять MDC из {@code MDC.getMDCAdapter()};
     * в JUnit-окружении адаптер может быть null → NPE. Поэтому ставим хотя бы Map.of().
     */
    private static LoggingEvent newEventWithMdc(Level level, String loggerName, String message,
                                                Map<String, String> mdc, Object... args) {
        LoggerContext ctx = new LoggerContext();
        Logger logger = ctx.getLogger(loggerName);
        LoggingEvent event = new LoggingEvent("fqcn", logger, level, message, null, args);
        event.setLoggerName(loggerName);
        event.setMDCPropertyMap(mdc);
        MDC.clear();
        return event;
    }

    private static JsonNode parse(byte[] bytes) {
        return MAPPER.readTree(bytes);
    }
}
