package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.Origin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Адаптер producer-ack → {@code HttpResponse}. Status 200 на успех, 500 на ошибку;
 * offset/partition/topic — в {@code HttpAttributes}.
 */
class KafkaProducerHttpResponseTest {

    @Test
    void onSuccess_shouldEmit200AndAttributes() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("t.orders", 3), 42L, 0, 0L, 0, 0);

        KafkaProducerHttpResponse response = new KafkaProducerHttpResponse(metadata, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getReasonPhrase()).isNull();
        assertThat(response.getOrigin()).isEqualTo(Origin.REMOTE);
        assertThat(response.getAttributes())
                .containsEntry("offset", 42L)
                .containsEntry("partition", 3)
                .containsEntry("topic", "t.orders");
    }

    @Test
    void onException_shouldEmit500AndRootCauseMessage() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("t.orders", 0), -1L, 0, 0L, 0, 0);
        Exception wrapped = new RuntimeException("outer", new IllegalStateException("root cause"));

        KafkaProducerHttpResponse response = new KafkaProducerHttpResponse(metadata, wrapped);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getReasonPhrase())
                .as("ExceptionUtils.getRootCauseMessage возвращает 'тип: сообщение' самого глубокого cause")
                .contains("root cause");
    }

    @Test
    void onSyncSendThrow_shouldNotNpe_whenMetadataIsNull() {
        // Sync-throw кейс: producer.send бросил исходное исключение (full buffer / closed),
        // metadata в callback не передаётся. Раньше конструктор падал с NPE на
        // metadata.offset(). Теперь — пустые атрибуты, статус 500.
        Exception ex = new RuntimeException("buffer-exhausted");

        KafkaProducerHttpResponse response = new KafkaProducerHttpResponse(null, ex);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getReasonPhrase()).contains("buffer-exhausted");
        assertThat(response.getAttributes())
                .as("при metadata=null атрибуты пустые, NPE не возникает")
                .isEmpty();
    }
}
