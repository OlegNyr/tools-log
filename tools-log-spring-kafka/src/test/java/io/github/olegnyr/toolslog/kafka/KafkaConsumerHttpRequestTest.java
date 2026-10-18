package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Origin;

import java.nio.charset.StandardCharsets;

import static io.github.olegnyr.toolslog.kafka.SaverForLogPayload.HEADER_ATTRIBUTE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Адаптер {@code ConsumerRecord} → {@code HttpRequest} для consumer-side логирования.
 * Origin = REMOTE (сообщение пришло из брокера). Тело извлекается из spec-заголовка
 * {@link SaverForLogPayload#HEADER_ATTRIBUTE_NAME}, чтобы лог содержал raw-payload.
 */
class KafkaConsumerHttpRequestTest {

    @Test
    void httpFields_shouldExposeKafkaCoordinates() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(
                "t.orders", 5, 1024L, 1_700_000_000_000L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                -1, -1, "k", "v", new RecordHeaders(), java.util.Optional.empty());

        KafkaConsumerHttpRequest<String, String> req =
                new KafkaConsumerHttpRequest<>(rec, "orders-group", null, KafkaBodyMode.BODY);

        assertThat(req.getScheme()).isEqualTo("kafka");
        assertThat(req.getHost()).isEqualTo("t.orders");
        assertThat(req.getPath()).isEqualTo("t.orders");
        assertThat(req.getOrigin()).isEqualTo(Origin.REMOTE);
        assertThat(req.getQuery())
                .as("формат: KafkaUtils.format(record) + '/' + groupId")
                .contains("t.orders", "orders-group");
    }

    @Test
    void getHeaders_shouldExposeRecordHeadersAndKey_butSkipPayloadHeader() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Trace-Id", "trace".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(HEADER_ATTRIBUTE_NAME, "raw".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(
                "t", 0, 0L, "key-1", "v1");
        headers.forEach(h -> rec.headers().add(h));

        KafkaConsumerHttpRequest<String, String> req =
                new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.BODY);

        assertThat(req.getHeaders().get("X-Trace-Id")).containsExactly("trace");
        assertThat(req.getHeaders().get("key")).containsExactly("key-1");
        assertThat(req.getHeaders().get(HEADER_ATTRIBUTE_NAME))
                .as("payload-header (attribute.<class>) должен быть отфильтрован — это служебное")
                .isNull();
    }

    @Test
    void getHeaders_shouldFilterStageMarkerHeader_addedByLogbookRecordInterceptor() {
        // LogbookKafkaRecordInterceptor кладёт служебный UUID-header с префиксом
        // STAGE_HEADER_PREFIX (= ResponseProcessingStage FQN). Он не должен утекать
        // в выходной лог как "header.<...>" — фильтруем по префиксу.
        String stageHeaderName = LogbookKafkaRecordInterceptor.STAGE_HEADER_PREFIX
                + "-some-instance-uuid";
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("X-Trace-Id", "trace".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(stageHeaderName, "stage-uuid".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        headers.forEach(h -> rec.headers().add(h));

        KafkaConsumerHttpRequest<String, String> req =
                new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.BODY);

        assertThat(req.getHeaders().get("X-Trace-Id")).containsExactly("trace");
        assertThat(req.getHeaders().get(stageHeaderName))
                .as("Header с префиксом STAGE_HEADER_PREFIX должен быть отфильтрован")
                .isNull();
    }

    @Test
    void withBody_shouldExtractPayloadFromHeader_whenPresent() throws Exception {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(HEADER_ATTRIBUTE_NAME, "{\"raw\":\"json\"}".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(
                "t", 0, 0L, 0L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                -1, -1, "k", "deserialized-value", headers, java.util.Optional.empty());

        HttpRequest withBody =
                new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.BODY).withBody();

        assertThat(withBody.getBodyAsString()).isEqualTo("{\"raw\":\"json\"}");
    }

    @Test
    void withBody_shouldFallbackToValue_whenPayloadHeaderAbsent() throws Exception {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "fallback-value");

        HttpRequest withBody =
                new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.BODY).withBody();

        assertThat(withBody.getBodyAsString()).isEqualTo("fallback-value");
    }

    @Test
    void withBody_shouldReturnEmpty_whenFormModeOff() throws Exception {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");

        HttpRequest withBody =
                new KafkaConsumerHttpRequest<>(rec, "g", null, KafkaBodyMode.OFF).withBody();

        assertThat(withBody.getBodyAsString()).isEmpty();
        assertThat(withBody.getBody()).isEmpty();
    }
}
