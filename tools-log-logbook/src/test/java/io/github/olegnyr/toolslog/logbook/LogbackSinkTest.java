package io.github.olegnyr.toolslog.logbook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;
import org.zalando.logbook.Precorrelation;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Тесты {@link LogbackSink} — направление incoming/outgoing, KV-структура,
 * склейка multi-value заголовков. Используем {@link ListAppender} для перехвата
 * событий и валидации key-value pairs.
 */
class LogbackSinkTest {

    private static final String BASE = "toolslog.test.logbook";

    private Logger incomingLogger;
    private Logger outgoingLogger;
    private ListAppender<ILoggingEvent> incomingAppender;
    private ListAppender<ILoggingEvent> outgoingAppender;
    private LogbackSink sink;

    @BeforeEach
    void setUp() {
        incomingLogger = (Logger) LoggerFactory.getLogger(BASE + ".incoming");
        outgoingLogger = (Logger) LoggerFactory.getLogger(BASE + ".outgoing");
        incomingLogger.setLevel(Level.INFO);
        outgoingLogger.setLevel(Level.INFO);

        incomingAppender = new ListAppender<>();
        outgoingAppender = new ListAppender<>();
        incomingAppender.start();
        outgoingAppender.start();
        incomingLogger.addAppender(incomingAppender);
        outgoingLogger.addAppender(outgoingAppender);

        sink = new LogbackSink(LoggerFactory.getLogger(BASE));
    }

    @AfterEach
    void tearDown() {
        incomingLogger.detachAppender(incomingAppender);
        outgoingLogger.detachAppender(outgoingAppender);
    }

    @Test
    void isActive_shouldReturnTrue_whenAtLeastOneLoggerEnabledAtInfo() {
        assertThat(sink.isActive()).isTrue();
    }

    @Test
    void isActive_shouldReturnFalse_whenBothLoggersBelowInfo() {
        incomingLogger.setLevel(Level.WARN);
        outgoingLogger.setLevel(Level.WARN);

        assertThat(sink.isActive()).isFalse();
    }

    @Test
    void writeRequest_shouldGoToIncomingLogger_whenOriginRemote() throws Exception {
        HttpRequest request = stubRequest(Origin.REMOTE, "POST", "HTTP/1.1",
                HttpHeaders.of("X-Trace", "abc-123"), "{\"k\":\"v\"}");

        sink.write(stubPrecorrelation("corr-1"), request);

        assertThat(outgoingAppender.list).isEmpty();
        assertThat(incomingAppender.list).hasSize(1);
        ILoggingEvent ev = incomingAppender.list.getFirst();
        assertThat(ev.getMessage()).isEqualTo("{\"k\":\"v\"}");
        Map<String, Object> kv = toKvMap(ev);
        assertThat(kv).containsEntry("correlation", "corr-1")
                .containsEntry("operation", "request")
                .containsEntry("origin", "incoming")
                .containsEntry("method", "POST")
                .containsEntry("protocolVersion", "HTTP/1.1")
                .containsEntry("header.X-Trace", "abc-123");
    }

    @Test
    void writeRequest_shouldGoToOutgoingLogger_whenOriginLocal() throws Exception {
        HttpRequest request = stubRequest(Origin.LOCAL, "GET", "HTTP/1.1", HttpHeaders.empty(), "");

        sink.write(stubPrecorrelation("corr-2"), request);

        assertThat(incomingAppender.list).isEmpty();
        assertThat(outgoingAppender.list).hasSize(1);
        Map<String, Object> kv = toKvMap(outgoingAppender.list.getFirst());
        assertThat(kv).containsEntry("origin", "outgoing");
    }

    @Test
    void writeResponse_shouldEmitDurationAndReason() throws Exception {
        HttpRequest request = stubRequest(Origin.LOCAL, "GET", "HTTP/1.1", HttpHeaders.empty(), "");
        HttpResponse response = stubResponse(Origin.LOCAL, 200, "OK", "HTTP/1.1",
                HttpHeaders.empty(), "body");

        sink.write(stubCorrelation("corr-3", Duration.ofMillis(123)), request, response);

        assertThat(outgoingAppender.list).hasSize(1);
        Map<String, Object> kv = toKvMap(outgoingAppender.list.getFirst());
        assertThat(kv).containsEntry("correlation", "corr-3")
                .containsEntry("operation", "response")
                .containsEntry("origin", "outgoing")
                .containsEntry("duration", 123L)
                .containsEntry("reason", "OK");
    }

    @Test
    void writeRequest_shouldJoinMultiValueHeaders_withCommaSpace() throws Exception {
        HttpRequest request = stubRequest(Origin.REMOTE, "GET", "HTTP/1.1",
                HttpHeaders.of("Accept", "text/plain", "application/json"), "");

        sink.write(stubPrecorrelation("corr-4"), request);

        Map<String, Object> kv = toKvMap(incomingAppender.list.getFirst());
        assertThat(kv).containsEntry("header.Accept", "text/plain, application/json");
    }

    // ---- Helpers ----

    private static Map<String, Object> toKvMap(ILoggingEvent event) {
        Map<String, Object> result = new java.util.HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(p -> result.put(p.key, p.value));
        }
        return result;
    }

    private static HttpRequest stubRequest(Origin origin, String method, String protocol,
                                                                                  HttpHeaders headers, String body) throws Exception {
        HttpRequest req = mock(HttpRequest.class);
        lenient().when(req.getOrigin()).thenReturn(origin);
        lenient().when(req.getMethod()).thenReturn(method);
        lenient().when(req.getProtocolVersion()).thenReturn(protocol);
        lenient().when(req.getHeaders()).thenReturn(headers);
        lenient().when(req.getBodyAsString()).thenReturn(body);
        lenient().when(req.getAttributes()).thenReturn(org.zalando.logbook.attributes.HttpAttributes.EMPTY);
        lenient().when(req.getRemote()).thenReturn("");
        lenient().when(req.getScheme()).thenReturn("http");
        lenient().when(req.getHost()).thenReturn("localhost");
        lenient().when(req.getPort()).thenReturn(java.util.Optional.empty());
        lenient().when(req.getPath()).thenReturn("/test");
        lenient().when(req.getQuery()).thenReturn("");
        return req;
    }

    private static HttpResponse stubResponse(Origin origin, int status, String reason,
                                                                                      String protocol, HttpHeaders headers, String body) throws Exception {
        HttpResponse res = mock(HttpResponse.class);
        lenient().when(res.getOrigin()).thenReturn(origin);
        lenient().when(res.getStatus()).thenReturn(status);
        lenient().when(res.getReasonPhrase()).thenReturn(reason);
        lenient().when(res.getProtocolVersion()).thenReturn(protocol);
        lenient().when(res.getHeaders()).thenReturn(headers);
        lenient().when(res.getBodyAsString()).thenReturn(body);
        lenient().when(res.getAttributes()).thenReturn(org.zalando.logbook.attributes.HttpAttributes.EMPTY);
        return res;
    }

    private static Precorrelation stubPrecorrelation(String id) {
        Precorrelation pre = mock(Precorrelation.class);
        when(pre.getId()).thenReturn(id);
        return pre;
    }

    private static Correlation stubCorrelation(String id, Duration duration) {
        Correlation corr = mock(Correlation.class);
        when(corr.getId()).thenReturn(id);
        when(corr.getDuration()).thenReturn(duration);
        return corr;
    }
}
