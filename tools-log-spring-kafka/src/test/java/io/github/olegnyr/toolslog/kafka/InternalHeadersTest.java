package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.zalando.logbook.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Служебные заголовки библиотеки не попадают в журнал ни потребителя, ни продьюсера.
 */
class InternalHeadersTest {

    private static final List<String> INTERNAL_HEADERS = List.of(
            SaverForLogPayload.HEADER_ATTRIBUTE_NAME,
            LogbookKafkaRecordInterceptor.STAGE_HEADER_PREFIX + "-" + UUID.randomUUID(),
            LogbookKafkaRecordInterceptor.STAGE_HEADER_PREFIX + UUID.randomUUID());

    static Stream<Arguments> adapters() {
        Function<Headers, HttpHeaders> consumer = headers -> {
            ConsumerRecord<String, String> rec = new ConsumerRecord<>("orders", 0, 0L, "k", "v");
            headers.forEach(h -> rec.headers().add(h));
            return new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.OFF).getHeaders();
        };
        Function<Headers, HttpHeaders> producer = headers -> new KafkaProducerHttpRequest<>(
                new ProducerRecord<>("orders.DLT", null, "k", "v", headers), null, KafkaBodyMode.OFF,
                Object::toString).getHeaders();
        return Stream.of(Arguments.of("consumer", consumer), Arguments.of("producer", producer));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("Служебные заголовки отфильтрованы, прикладной заголовок и ключ остаются")
    void getHeaders_shouldSkipInternalHeaders_whenRecordCarriesThem(String side,
            Function<Headers, HttpHeaders> adapter) {
        Headers headers = new RecordHeaders();
        headers.add("X-Trace-Id", "trace".getBytes(StandardCharsets.UTF_8));
        INTERNAL_HEADERS.forEach(name -> headers.add(name, "{\"secret\":1}".getBytes(StandardCharsets.UTF_8)));

        HttpHeaders result = adapter.apply(headers);

        assertThat(result.keySet()).containsExactlyInAnyOrder("X-Trace-Id", "key");
        assertThat(result.get("key")).containsExactly("k");
    }

    @Test
    @DisplayName("null и прикладные имена не служебные")
    void isInternal_shouldReturnFalse_whenKeyIsNullOrApplication() {
        assertThat(InternalHeaders.isInternal(null)).isFalse();
        assertThat(InternalHeaders.isInternal("X-Trace-Id")).isFalse();
        assertThat(InternalHeaders.isInternal(SaverForLogPayload.HEADER_ATTRIBUTE_NAME)).isTrue();
    }
}
