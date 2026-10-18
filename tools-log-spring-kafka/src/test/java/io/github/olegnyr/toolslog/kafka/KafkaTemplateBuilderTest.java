package io.github.olegnyr.toolslog.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.zalando.logbook.Logbook;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaTemplateBuilderTest {

    private static KafkaTemplateBuilder<String> minimalBuilder(Map<String, Object> props) {
        return new KafkaTemplateBuilder<String>()
                .producerProperties(props)
                .jsonMapper(JsonMapper.builder().build())
                .logbook(mock(Logbook.class))
                .meterRegistry(new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Без аудит-полей перехватчики сервиса не трогаются")
    void build_shouldKeepServiceInterceptors_whenAuditKeysNotSet() {
        KafkaTemplate<String, String> template = minimalBuilder(Map.of(
                "bootstrap.servers", "localhost:9092",
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "com.example.Interceptor")).build();

        Map<String, Object> config = template.getProducerFactory().getConfigurationProperties();

        assertThat(config.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG)).isEqualTo("com.example.Interceptor");
        assertThat(config).doesNotContainKey(AuditHeaderProducerInterceptor.MDC_KEYS_CONFIG);
    }

    @Test
    @DisplayName("Аудит-перехватчик добавляется к перехватчикам сервиса, а не заменяет их")
    void build_shouldAppendAuditInterceptor_whenAuditKeysSet() {
        KafkaTemplate<String, String> template = minimalBuilder(Map.of(
                "bootstrap.servers", "localhost:9092",
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "com.example.Interceptor"))
                .auditMdcKeys(List.of("user.id"))
                .build();

        Map<String, Object> config = template.getProducerFactory().getConfigurationProperties();

        assertThat(config.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG))
                .isEqualTo(List.of("com.example.Interceptor", AuditHeaderProducerInterceptor.class));
        assertThat(config.get(AuditHeaderProducerInterceptor.MDC_KEYS_CONFIG)).isEqualTo(List.of("user.id"));
    }

    @Test
    @DisplayName("Аудит-перехватчик не добавляется второй раз")
    void withInterceptor_shouldNotDuplicate_whenAlreadyConfigured() {
        List<Object> interceptors = KafkaTemplateBuilder.withInterceptor(
                List.of(AuditHeaderProducerInterceptor.class.getName()));

        assertThat(interceptors).containsExactly(AuditHeaderProducerInterceptor.class.getName());
    }
}
