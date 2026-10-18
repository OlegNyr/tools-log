package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Продьюсер копирует в заголовки только настроенные поля MDC")
    void onSend_shouldCopyConfiguredMdcKeys_whenPresentInMdc() {
        AuditHeaderProducerInterceptor interceptor = new AuditHeaderProducerInterceptor();
        interceptor.configure(Map.of(AuditHeaderProducerInterceptor.MDC_KEYS_CONFIG, "user.id, client.ip"));
        MDC.put("user.id", "u-1");
        MDC.put("other", "x");

        ProducerRecord<Object, Object> sent = interceptor.onSend(new ProducerRecord<>("t", "k", "v"));

        assertThat(new String(sent.headers().lastHeader("user.id").value(), StandardCharsets.UTF_8))
                .isEqualTo("u-1");
        assertThat(sent.headers().lastHeader("client.ip")).isNull();
        assertThat(sent.headers().lastHeader("other")).isNull();
    }

    @Test
    @DisplayName("Продьюсер без настройки полей заголовки не пишет")
    void onSend_shouldNotAddHeaders_whenKeysNotConfigured() {
        AuditHeaderProducerInterceptor interceptor = new AuditHeaderProducerInterceptor();
        interceptor.configure(Map.of());
        MDC.put("user.id", "u-1");

        ProducerRecord<Object, Object> sent = interceptor.onSend(new ProducerRecord<>("t", "k", "v"));

        assertThat(sent.headers().toArray()).isEmpty();
    }

    @Test
    @DisplayName("Продьюсер не задваивает уже выставленный заголовок")
    void onSend_shouldKeepExistingHeader_whenRepublished() {
        AuditHeaderProducerInterceptor interceptor = new AuditHeaderProducerInterceptor();
        interceptor.configure(Map.of(AuditHeaderProducerInterceptor.MDC_KEYS_CONFIG, List.of("user.id")));
        MDC.put("user.id", "u-2");
        ProducerRecord<Object, Object> record = new ProducerRecord<>("t", "k", "v");
        record.headers().add("user.id", "u-1".getBytes(StandardCharsets.UTF_8));

        ProducerRecord<Object, Object> sent = interceptor.onSend(record);

        assertThat(sent.headers().headers("user.id")).hasSize(1);
    }

    @Test
    @DisplayName("Консьюмер кладёт поля в MDC на время записи и снимает после")
    void intercept_shouldPutHeadersIntoMdc_andAfterRecordShouldRemoveThem() {
        AuditMdcRecordInterceptor<String, String> interceptor = new AuditMdcRecordInterceptor<>(List.of("user.id"));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        record.headers().add("user.id", "u-1".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);
        String during = MDC.get("user.id");
        interceptor.afterRecord(record, null);

        assertThat(during).isEqualTo("u-1");
        assertThat(MDC.get("user.id")).isNull();
    }
}
