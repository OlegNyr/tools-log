package io.github.olegnyr.toolslog.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.olegnyr.toolslog.logbook.LogbackSink;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.zalando.logbook.Logbook;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link LogbookContextKafkaProducerInterceptor} — наш ProducerPostProcessor, оборачивающий
 * KafkaProducer для зеркалирования send'ов в Logbook + восстановления MDC в callback'е.
 *
 * <p>Полноценный {@code Logbook.create()} + {@link LogbackSink} даёт нам реальный путь
 * request→logger.info, и мы перехватываем эти эвенты {@link ListAppender}'ом — как делали
 * для {@code LogbackSinkTest}.
 */
class LogbookContextKafkaProducerInterceptorTest {

    private static final String LOG_BASE = "toolslog.test.kafkaproducer";

    private Logger outgoingLogger;
    private Logger incomingLogger;
    private ListAppender<ILoggingEvent> outgoingAppender;
    private ListAppender<ILoggingEvent> incomingAppender;
    private LogbookContextKafkaProducerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        // LogbackSink логирует request на <name>.outgoing, response на <name>.incoming
        // (Producer-side: request Origin.LOCAL → outgoing, response Origin.REMOTE → incoming).
        outgoingLogger = (Logger) LoggerFactory.getLogger(LOG_BASE + ".outgoing");
        incomingLogger = (Logger) LoggerFactory.getLogger(LOG_BASE + ".incoming");
        outgoingLogger.setLevel(Level.INFO);
        incomingLogger.setLevel(Level.INFO);
        outgoingAppender = new ListAppender<>();
        incomingAppender = new ListAppender<>();
        outgoingAppender.start();
        incomingAppender.start();
        outgoingLogger.addAppender(outgoingAppender);
        incomingLogger.addAppender(incomingAppender);

        Logbook logbook = Logbook.builder()
                .sink(new LogbackSink(LoggerFactory.getLogger(LOG_BASE)))
                .build();
        Function<String, String> toString = s -> s;
        interceptor = new LogbookContextKafkaProducerInterceptor<>(
                logbook, null, KafkaBodyMode.BODY, toString);
    }

    @AfterEach
    void tearDown() {
        outgoingLogger.detachAppender(outgoingAppender);
        incomingLogger.detachAppender(incomingAppender);
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void apply_shouldReturnDelegatingProducer_thatLogsRequestImmediately() {
        Producer<String, String> rawProducer = mock(Producer.class);
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        Producer<String, String> wrapped = interceptor.apply(rawProducer);
        ProducerRecord<String, String> rec = new ProducerRecord<>("t.orders", "k1", "v1");

        wrapped.send(rec, (m, e) -> { /* no-op user callback */ });

        // Request — синхронно перед фактическим send'ом.
        assertThat(outgoingAppender.list)
                .as("В outgoing-логгер должен прилететь request kafka-сообщения").isNotEmpty();
        Map<String, Object> kv = toKvMap(outgoingAppender.list.getFirst());
        assertThat(kv).containsEntry("operation", "request")
                .containsEntry("origin", "outgoing")
                // LogbackSink.fillHeader пишет каждый header как "header.X". Псевдо-header
                // "key" в KafkaProducerHttpRequest.getHeaders() становится "header.key".
                .containsEntry("header.key", "k1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_shouldDelegateToRealProducer_andInvokeUserCallback() {
        Producer<String, String> rawProducer = mock(Producer.class);
        AtomicReference<Callback> capturedCallback = new AtomicReference<>();
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenAnswer(inv -> {
                    capturedCallback.set(inv.getArgument(1, Callback.class));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });

        Producer<String, String> wrapped = interceptor.apply(rawProducer);
        ProducerRecord<String, String> rec = new ProducerRecord<>("t", "k", "v");
        AtomicReference<RecordMetadata> userMeta = new AtomicReference<>();
        AtomicReference<Exception> userExc = new AtomicReference<>();

        wrapped.send(rec, (m, e) -> {
            userMeta.set(m);
            userExc.set(e);
        });

        // Симулируем broker-ack из реального producer'а:
        RecordMetadata meta = new RecordMetadata(new TopicPartition("t", 0), 17L, 0, 0L, 0, 0);
        capturedCallback.get().onCompletion(meta, null);

        assertThat(userMeta).hasValue(meta);
        assertThat(userExc.get()).isNull();
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(rawProducer).send(captor.capture(), any(Callback.class));
        assertThat(captor.getValue().key()).isEqualTo("k");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCompletion_shouldRestoreMdcContextSnapshot_capturedAtSendTime() {
        // MdcThreadLocalAccessor зарегистрирован SPI-файлом
        // META-INF/services/io.micrometer.context.ThreadLocalAccessor → ContextSnapshotFactory
        // captureAll берёт MDC, setThreadLocals в callback'е восстанавливает.
        Producer<String, String> rawProducer = mock(Producer.class);
        AtomicReference<Callback> capturedCallback = new AtomicReference<>();
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenAnswer(inv -> {
                    capturedCallback.set(inv.getArgument(1, Callback.class));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        Producer<String, String> wrapped = interceptor.apply(rawProducer);

        MDC.put("traceId", "trace-A");
        wrapped.send(new ProducerRecord<>("t", "k", "v"), (m, e) -> { /* no-op */ });
        MDC.clear(); // имитируем переключение потока — после send нет ничего в MDC

        capturedCallback.get().onCompletion(
                new RecordMetadata(new TopicPartition("t", 0), 1L, 0, 0L, 0, 0), null);

        // Response пишется на incoming-логгер; его MDCPropertyMap должен содержать
        // traceId, восстановленный ContextSnapshot'ом.
        assertThat(incomingAppender.list).isNotEmpty();
        ILoggingEvent responseEvent = incomingAppender.list.getLast();
        assertThat(responseEvent.getMDCPropertyMap())
                .as("ContextSnapshot должен восстановить MDC.traceId внутри completion-callback'а")
                .containsEntry("traceId", "trace-A");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCompletion_shouldEmitResponseEvent_whenSendFailed() {
        Producer<String, String> rawProducer = mock(Producer.class);
        AtomicReference<Callback> capturedCallback = new AtomicReference<>();
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenAnswer(inv -> {
                    capturedCallback.set(inv.getArgument(1, Callback.class));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        Producer<String, String> wrapped = interceptor.apply(rawProducer);

        wrapped.send(new ProducerRecord<>("t", "k", "v"), (m, e) -> { /* no-op */ });
        RecordMetadata meta = new RecordMetadata(new TopicPartition("t", 0), -1L, 0, 0L, 0, 0);
        capturedCallback.get().onCompletion(meta, new RuntimeException("kafka-broker-down"));

        assertThat(incomingAppender.list)
                .as("Response должен быть записан, даже когда send упал").isNotEmpty();
        Map<String, Object> kv = toKvMap(incomingAppender.list.getFirst());
        assertThat(kv).containsEntry("operation", "response")
                .containsEntry("origin", "incoming")
                .containsKey("duration");
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_shouldLogResponseAndRethrow_whenProducerSendThrowsSync() {
        // Sync-throw: producer.send бросает синхронно (full buffer/closed). Callback не
        // вызывается — наш fix в LoggerProducer.send ловит это и сам пишет response с 500
        // через пайплайн Logbook'а, чтобы request-event не "висел" без статуса.
        Producer<String, String> rawProducer = mock(Producer.class);
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenThrow(new IllegalStateException("buffer-exhausted"));
        Producer<String, String> wrapped = interceptor.apply(rawProducer);

        Throwable thrown = catchThrowable(() ->
                wrapped.send(new ProducerRecord<>("t", "k", "v")));

        assertThat(thrown)
                .as("Исключение должно пробросываться наверх, чтобы вызывающий узнал об ошибке")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("buffer-exhausted");
        assertThat(incomingAppender.list)
                .as("Response должен быть записан несмотря на синхронный throw").isNotEmpty();
        Map<String, Object> kv = toKvMap(incomingAppender.list.getLast());
        assertThat(kv).containsEntry("operation", "response")
                .containsEntry("origin", "incoming");
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_singleArg_shouldDelegateToTwoArgSend() {
        Producer<String, String> rawProducer = mock(Producer.class);
        when(rawProducer.send(any(ProducerRecord.class), any(Callback.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        Producer<String, String> wrapped = interceptor.apply(rawProducer);
        wrapped.send(new ProducerRecord<>("t", "k", "v"));

        verify(rawProducer).send(any(ProducerRecord.class), any(Callback.class));
    }

    // ---- helpers ----

    private static Map<String, Object> toKvMap(ILoggingEvent event) {
        Map<String, Object> result = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(p -> result.put(p.key, p.value));
        }
        return result;
    }
}
