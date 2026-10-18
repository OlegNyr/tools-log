package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Origin;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Адаптер {@code ProducerRecord} → {@code HttpRequest} для Logbook'а.
 * Origin = LOCAL (запрос от нашего producer'а наружу). Тело и заголовки управляемы
 * через {@link KafkaBodyMode}.
 */
class KafkaProducerHttpRequestTest {

    private static final Function<String, String> TO_STRING = Function.identity();

    @Test
    void httpFields_shouldExposeKafkaCoordinates() {
        ProducerRecord<String, String> rec = new ProducerRecord<>("t.orders", 7, 1_700_000_000_000L, "k1", "v1");

        KafkaProducerHttpRequest<String, String> req =
                new KafkaProducerHttpRequest<>(rec, null, KafkaBodyMode.BODY, TO_STRING);

        assertThat(req.getScheme()).isEqualTo("kafka");
        assertThat(req.getHost()).isEqualTo("broker");
        assertThat(req.getPath()).isEqualTo("/t.orders");
        assertThat(req.getQuery()).isEqualTo("partition=7&timestamp=1700000000000");
        assertThat(req.getOrigin()).isEqualTo(Origin.LOCAL);
        assertThat(req.getMethod()).isEmpty();
        assertThat(req.getRemote()).isEmpty();
        assertThat(req.getProtocolVersion()).isEmpty();
        assertThat(req.getPort()).isEmpty();
    }

    @Test
    void getHeaders_shouldExposeRecordHeadersAndKey() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Trace-Id", "abc-123".getBytes(StandardCharsets.UTF_8)));
        ProducerRecord<String, String> rec =
                new ProducerRecord<>("t", null, "key-1", "v1", headers);

        KafkaProducerHttpRequest<String, String> req =
                new KafkaProducerHttpRequest<>(rec, null, KafkaBodyMode.BODY, TO_STRING);

        assertThat(req.getHeaders().get("X-Trace-Id")).containsExactly("abc-123");
        assertThat(req.getHeaders().get("key")).containsExactly("key-1");
    }

    @Test
    void withBody_shouldExtractPayload_whenFormModeBody() throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>("t", "k", "hello");

        HttpRequest withBody =
                new KafkaProducerHttpRequest<>(rec, null, KafkaBodyMode.BODY, TO_STRING).withBody();

        assertThat(withBody.getBodyAsString()).isEqualTo("hello");
        assertThat(new String(withBody.getBody(), withBody.getCharset())).isEqualTo("hello");
    }

    @Test
    void withBody_shouldReturnEmpty_whenFormModeOff() throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>("t", "k", "hello");

        HttpRequest withBody =
                new KafkaProducerHttpRequest<>(rec, null, KafkaBodyMode.OFF, TO_STRING).withBody();

        assertThat(withBody.getBodyAsString()).isEmpty();
        assertThat(withBody.getBody()).isEmpty();
    }

    @Test
    void withBody_shouldFallbackToNullPayload_whenSerializerThrows() throws Exception {
        Function<String, String> failing = v -> {
            throw new RuntimeException("boom");
        };
        ProducerRecord<String, String> rec = new ProducerRecord<>("t", "k", "v");

        // toString-сериализатор бросает исключение — request.withBody() ловит это и
        // делает payload=null. Полагаемся на это для устойчивости лог-конвейера.
        HttpRequest withBody =
                new KafkaProducerHttpRequest<>(rec, null, KafkaBodyMode.BODY, failing).withBody();

        // getBodyAsString вернёт null (value == null после fallback) — Logbook это допускает.
        assertThat(withBody.getBodyAsString()).isNull();
    }
}
