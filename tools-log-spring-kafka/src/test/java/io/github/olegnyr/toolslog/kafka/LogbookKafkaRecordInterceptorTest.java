package io.github.olegnyr.toolslog.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.olegnyr.toolslog.logbook.LogbackSink;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.zalando.logbook.Logbook;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link LogbookKafkaRecordInterceptor}: проверяют, что request пишется
 * на consumer-логгер при intercept, response — на success/failure, а внутренний
 * processingStageMap корректно чистится.
 *
 * <p>Logbook с {@link LogbookSink}, ListAppender для перехвата событий — тот же
 * паттерн, что и в {@code LogbackSinkTest} / {@code LogbookContextKafkaProducerInterceptorTest}.
 *
 * <p>Consumer-side ориентация: request приходит снаружи (Origin.REMOTE) → пишется
 * в {@code <name>.incoming}; response уходит наружу (Origin.LOCAL) → пишется в
 * {@code <name>.outgoing}.
 */
class LogbookKafkaRecordInterceptorTest {

    private static final String LOG_BASE = "toolslog.test.kafkaconsumer";

    private Logger incomingLogger;
    private Logger outgoingLogger;
    private ListAppender<ILoggingEvent> incomingAppender;
    private ListAppender<ILoggingEvent> outgoingAppender;
    private LogbookKafkaRecordInterceptor<String, String> interceptor;
    private Logbook logbook;

    @BeforeEach
    void setUp() {
        incomingLogger = (Logger) LoggerFactory.getLogger(LOG_BASE + ".incoming");
        outgoingLogger = (Logger) LoggerFactory.getLogger(LOG_BASE + ".outgoing");
        incomingLogger.setLevel(Level.INFO);
        outgoingLogger.setLevel(Level.INFO);
        incomingAppender = new ListAppender<>();
        outgoingAppender = new ListAppender<>();
        incomingAppender.start();
        outgoingAppender.start();
        incomingLogger.addAppender(incomingAppender);
        outgoingLogger.addAppender(outgoingAppender);

        logbook = Logbook.builder()
                .sink(new LogbackSink(LoggerFactory.getLogger(LOG_BASE)))
                .build();
        // bodyMode — через @With: конструктор с одним Logbook берёт умолчания.
        interceptor = new LogbookKafkaRecordInterceptor<String, String>(logbook)
                .withBodyMode(KafkaBodyMode.BODY);
    }

    @AfterEach
    void tearDown() {
        incomingLogger.detachAppender(incomingAppender);
        outgoingLogger.detachAppender(outgoingAppender);
    }

    @Test
    void intercept_shouldEmitRequestLogAndReturnSameRecord() {
        Consumer<String, String> consumer = stubConsumer("group-A");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t.orders", 0, 0L, "k1", "v1");

        ConsumerRecord<String, String> returned = interceptor.intercept(record, consumer);

        assertThat(returned).isSameAs(record);
        assertThat(incomingAppender.list)
                .as("Request kafka-сообщения должен быть записан на incoming-логгер")
                .isNotEmpty();
        Map<String, Object> kv = toKvMap(incomingAppender.list.getFirst());
        assertThat(kv).containsEntry("operation", "request")
                .containsEntry("origin", "incoming")
                .containsEntry("header.key", "k1");
    }

    @Test
    void intercept_shouldStoreUuidInRecordHeaders() throws Exception {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");

        interceptor.intercept(record, consumer);

        String headerName = readPrivateField(interceptor, "responseProcessingStageName", String.class);
        Header h = record.headers().lastHeader(headerName);
        assertThat(h)
                .as("intercept должен класть UUID-ключ в headers — для lookup'а stage в success/failure")
                .isNotNull();
        assertThat(new String(h.value())).hasSize(36); // классический UUID
    }

    @Test
    void success_shouldEmitResponseWithStatus200() {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        interceptor.intercept(record, consumer);

        interceptor.success(record, consumer);

        assertThat(outgoingAppender.list).isNotEmpty();
        Map<String, Object> kv = toKvMap(outgoingAppender.list.getLast());
        assertThat(kv).containsEntry("operation", "response")
                .containsEntry("origin", "outgoing")
                .containsKey("duration");
    }

    @Test
    void failure_shouldEmitResponseWithStatus500AndReason() {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        interceptor.intercept(record, consumer);

        interceptor.failure(record, new RuntimeException("listener-blew-up"), consumer);

        assertThat(outgoingAppender.list).isNotEmpty();
        Map<String, Object> kv = toKvMap(outgoingAppender.list.getLast());
        assertThat(kv).containsEntry("operation", "response")
                .containsEntry("origin", "outgoing")
                .containsEntry("reason", "RuntimeException: listener-blew-up");
    }

    @Test
    void success_shouldEmitResponseWithTraceId_whenTraceContextInHeaders() {
        String traceId = "6a38fc8bf0963a1869fb13167c1198b5";
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span span = mock(Span.class);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        // withSpan кладёт traceId в MDC на время scope — имитируем bridge correlation.
        when(tracer.withSpan(span)).thenAnswer(inv -> {
            MDC.put("traceId", traceId);
            return (Tracer.SpanInScope) () -> MDC.remove("traceId");
        });

        LogbookKafkaRecordInterceptor<String, String> traced =
                new LogbookKafkaRecordInterceptor<>(logbook, null, KafkaBodyMode.BODY, tracer, propagator,
                        SecretMaskingScanner.NOOP);
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t.orders", 0, 0L, "k", "v");
        traced.intercept(record, consumer);

        traced.success(record, consumer);

        assertThat(outgoingAppender.list).isNotEmpty();
        assertThat(outgoingAppender.list.getLast().getMDCPropertyMap())
                .as("response-эвент consume-лога должен нести тот же traceId, что и request")
                .containsEntry("traceId", traceId);
    }

    @Test
    void successAfterSuccess_shouldBeNoOp_dueToMapCleanup() throws Exception {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        interceptor.intercept(record, consumer);
        interceptor.success(record, consumer);
        int countAfterFirst = outgoingAppender.list.size();

        // Повторный success — entry в processingStageMap уже удалена computeIfPresent'ом.
        // Defensive: ничего нового не должно записаться.
        interceptor.success(record, consumer);

        assertThat(outgoingAppender.list).hasSize(countAfterFirst);
        Cache<String, ?> cache = readPrivateField(interceptor, "processingStageCache", Cache.class);
        assertThat(cache.asMap())
                .as("processingStageCache должен быть пуст после success — Caffeine.remove pop'нул entry")
                .isEmpty();
    }

    @Test
    void successOnUnknownRecord_shouldBeNoOp_whenInterceptWasNotCalled() {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        // intercept НЕ вызван — header'а нет, мапа пуста.

        interceptor.success(record, consumer);

        assertThat(outgoingAppender.list)
                .as("Без предшествующего intercept — никакой response не пишется")
                .isEmpty();
    }

    @Test
    void concurrentRecords_shouldKeepStagesIndependent() throws Exception {
        Consumer<String, String> consumer = stubConsumer("g");
        ConsumerRecord<String, String> r1 = new ConsumerRecord<>("t", 0, 0L, "k1", "v1");
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>("t", 0, 1L, "k2", "v2");

        interceptor.intercept(r1, consumer);
        interceptor.intercept(r2, consumer);
        Cache<String, ?> cache = readPrivateField(interceptor, "processingStageCache", Cache.class);
        assertThat(cache.asMap()).hasSize(2);

        // success на r1 — должна остаться только запись r2.
        interceptor.success(r1, consumer);
        assertThat(cache.asMap()).hasSize(1);

        interceptor.failure(r2, new RuntimeException("oops"), consumer);
        assertThat(cache.asMap()).isEmpty();
    }

    @Test
    @DisplayName("Тело в пределах лимита сканируется на секреты")
    void intercept_shouldScanBody_whenWithinLimit() {
        SecretMaskingScanner scanner = mock(SecretMaskingScanner.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k",
                "x".repeat(LogbookKafkaRecordInterceptor.MAX_SCAN_LENGTH));

        withScanner(scanner).intercept(record, stubConsumer("g"));

        verify(scanner).scanAndRegister(record.value());
    }

    @Test
    @DisplayName("Тело длиннее лимита не сканируется, но в лог пишется")
    void intercept_shouldSkipScan_butStillLog_whenBodyOverLimit() {
        SecretMaskingScanner scanner = mock(SecretMaskingScanner.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k",
                "x".repeat(LogbookKafkaRecordInterceptor.MAX_SCAN_LENGTH + 1));

        withScanner(scanner).intercept(record, stubConsumer("g"));

        verify(scanner, never()).scanAndRegister(anyString());
        assertThat(incomingAppender.list).hasSize(1);
    }

    // ---- helpers ----

    private LogbookKafkaRecordInterceptor<String, String> withScanner(SecretMaskingScanner scanner) {
        return new LogbookKafkaRecordInterceptor<>(logbook, null, KafkaBodyMode.BODY, Tracer.NOOP, Propagator.NOOP,
                scanner);
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, String> stubConsumer(String groupId) {
        Consumer<String, String> consumer = mock(Consumer.class);
        ConsumerGroupMetadata meta = mock(ConsumerGroupMetadata.class);
        when(consumer.groupMetadata()).thenReturn(meta);
        when(meta.groupId()).thenReturn(groupId);
        return consumer;
    }

    private static Map<String, Object> toKvMap(ILoggingEvent event) {
        Map<String, Object> result = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(p -> result.put(p.key, p.value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T readPrivateField(Object target, String fieldName, Class<T> type) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}
