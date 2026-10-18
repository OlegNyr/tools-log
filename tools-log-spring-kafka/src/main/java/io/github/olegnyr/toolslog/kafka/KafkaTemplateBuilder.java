package io.github.olegnyr.toolslog.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.zalando.logbook.Logbook;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fluent-builder типового {@link KafkaTemplate}{@code <String, V>} для исходящих
 * Kafka-потоков. Симметричен {@link KafkaListenerContainerFactoryBuilder} —
 * собирает в одно место обвязку producer'а, чтобы producer-бины отдельных потоков
 * сводились к сигнатуре бина и нескольким строкам сеттеров.
 *
 * <h2>Что внутри</h2>
 * <ul>
 *   <li>Сериализатор value: {@link JacksonJsonSerializer} (Spring Kafka 4 для Jackson 3)
 *       поверх общего {@link JsonMapper}. {@code noTypeInfo()} отключает
 *       Spring-Kafka {@code __TypeId__}-header (полиморфизм идёт через
 *       {@code @JsonTypeInfo} в payload).</li>
 *   <li>{@link LogbookContextKafkaProducerInterceptor} как
 *       {@code ProducerPostProcessor} — пишет request/response каждого {@code send}
 *       в Logbook + захватывает MDC через {@code ContextSnapshot}, чтобы trace
 *       не рвался на async-границе callback'а.</li>
 *   <li>{@link MicrometerProducerListener} — kafka_producer_* метрики
 *       (record-send-rate, batch-size и т. п.).</li>
 *   <li>{@code KafkaTemplate.setObservationEnabled(true)} — producer-side
 *       observation: {@code traceparent} (W3C) автоматически летит в headers,
 *       сама операция оборачивается span'ом {@code kafka send}.</li>
 *   <li>{@link AuditHeaderProducerInterceptor} — поля MDC в заголовки записи, если заданы
 *       {@link #auditMdcKeys(List)}.</li>
 * </ul>
 *
 * <h2>Использование</h2>
 * <pre>{@code
 * @Bean
 * public KafkaTemplate<String, OrderEvent> orderEventsKafkaTemplate(
 *         KafkaProperties props, JsonMapper jsonMapper, Logbook logbook,
 *         MeterRegistry meter) {
 *     return new KafkaTemplateBuilder<OrderEvent>()
 *             .producerProperties(props.buildProducerProperties())
 *             .jsonMapper(jsonMapper)
 *             .logbook(logbook)
 *             .meterRegistry(meter)
 *             .build();
 * }
 * }</pre>
 *
 * <p>Свойства продьюсера передаются готовым {@code Map<String, Object>}
 * (обычно {@code KafkaProperties.buildProducerProperties()}), чтобы библиотеке
 * не нужна была зависимость от {@code spring-boot-kafka}.
 *
 * <h2>Когда НЕ использовать</h2>
 * Для DLQ-template'а ({@code String}-key + {@code byte[]}-value producer) — там нет Jackson, нет
 * Logbook-обёртки (raw-bytes, сам факт отправки уже виден из лог-эвента первичного
 * listener'а).
 */
// PMD.CloseResource: JacksonJsonSerializer хранится внутри
// DefaultKafkaProducerFactory как Spring-managed bean — закрывается контейнером
// при shutdown'е.
@SuppressWarnings("PMD.CloseResource")
public final class KafkaTemplateBuilder<V> {

    private Map<String, Object> producerProperties;
    private JsonMapper jsonMapper;
    private Logbook logbook;
    private MeterRegistry meterRegistry;
    private List<String> auditMdcKeys = List.of();

    public KafkaTemplateBuilder<V> producerProperties(Map<String, Object> producerProperties) {
        this.producerProperties = producerProperties;
        return this;
    }

    public KafkaTemplateBuilder<V> jsonMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        return this;
    }

    public KafkaTemplateBuilder<V> logbook(Logbook logbook) {
        this.logbook = logbook;
        return this;
    }

    public KafkaTemplateBuilder<V> meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * Опционально: поля MDC, которые копируются в одноимённые заголовки каждой записи
     * (пара к {@link KafkaListenerContainerFactoryBuilder#auditMdcKeys(List)}). По умолчанию — нет.
     */
    public KafkaTemplateBuilder<V> auditMdcKeys(List<String> auditMdcKeys) {
        this.auditMdcKeys = List.copyOf(auditMdcKeys);
        return this;
    }

    public KafkaTemplate<String, V> build() {
        Objects.requireNonNull(producerProperties, "producerProperties is required");
        Objects.requireNonNull(jsonMapper, "jsonMapper is required");
        Objects.requireNonNull(logbook, "logbook is required");
        Objects.requireNonNull(meterRegistry, "meterRegistry is required");

        KafkaTemplate<String, V> template = new KafkaTemplate<>(buildProducerFactory());
        // W3C traceparent в headers + span "kafka send" в Micrometer Observation —
        // consumer-side подхватит traceId через ContainerProperties.setObservationEnabled.
        template.setObservationEnabled(true);
        return template;
    }

    private ProducerFactory<String, V> buildProducerFactory() {
        JacksonJsonSerializer<V> valueSerializer = new JacksonJsonSerializer<>(jsonMapper);
        valueSerializer.noTypeInfo();
        Map<String, Object> props = new HashMap<>(producerProperties);
        if (!auditMdcKeys.isEmpty()) {
            props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                    withInterceptor(props.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG)));
            props.put(AuditHeaderProducerInterceptor.MDC_KEYS_CONFIG, auditMdcKeys);
        }
        DefaultKafkaProducerFactory<String, V> factory = new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), valueSerializer);
        factory.addPostProcessor(new LogbookContextKafkaProducerInterceptor<>(
                logbook, null, KafkaBodyMode.BODY, jsonStringifier()));
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Добавляет {@link AuditHeaderProducerInterceptor} к перехватчикам из свойств сервиса, не
     * затирая их. Kafka принимает значение списком, строкой через запятую или классом.
     */
    static List<Object> withInterceptor(Object configured) {
        List<Object> interceptors = new ArrayList<>();
        if (configured instanceof Collection<?> many) {
            interceptors.addAll(many);
        } else if (configured instanceof String names) {
            Arrays.stream(names.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                    .forEach(interceptors::add);
        } else if (configured != null) {
            interceptors.add(configured);
        }
        if (!interceptors.contains(AuditHeaderProducerInterceptor.class)
                && !interceptors.contains(AuditHeaderProducerInterceptor.class.getName())) {
            interceptors.add(AuditHeaderProducerInterceptor.class);
        }
        return interceptors;
    }

    /**
     * Функция value→JSON для {@link LogbookContextKafkaProducerInterceptor} —
     * нужно ему, чтобы записать тело в Logbook-request. Любая ошибка сериализации
     * → null payload; {@code KafkaProducerHttpRequest.withBody} умеет с этим жить
     * (внутри тоже try/catch).
     */
    private Function<V, String> jsonStringifier() {
        return value -> {
            try {
                return jsonMapper.writeValueAsString(value);
            } catch (Exception e) {
                return null;
            }
        };
    }
}
