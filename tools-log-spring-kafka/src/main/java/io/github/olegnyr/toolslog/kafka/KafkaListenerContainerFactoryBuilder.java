package io.github.olegnyr.toolslog.kafka;

import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.zalando.logbook.Logbook;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent-builder типового {@link ConcurrentKafkaListenerContainerFactory}{@code <String, V>}
 * для входящих Kafka-потоков. Собирает в одно место однотипную обвязку, чтобы
 * Kafka-конфиги отдельных потоков сводились к сигнатуре бина и нескольким строкам
 * с указанием конкретного {@link JavaType}.
 *
 * <h2>Что внутри</h2>
 * <ul>
 *   <li>Десериализатор value: {@link ErrorHandlingDeserializer} → {@link SaverForLogPayload}
 *       → {@link JacksonJsonDeserializer} (Jackson 3 поверх общего {@link JsonMapper}).
 *       Битый JSON и unknown subtypes идут в DLQ через общий {@link DefaultErrorHandler}.</li>
 *   <li>{@code ignoreTypeHeaders()} — отключает Spring-Kafka {@code __TypeId__}; полиморфизм
 *       идёт через {@code @JsonTypeInfo} в payload.</li>
 *   <li>{@link MicrometerConsumerListener} — kafka_consumer_* метрики (records-lag и т. п.).</li>
 *   <li>{@link LogbookKafkaRecordInterceptor} — request/response Logbook на каждый record
 *       + tracing-контекст.</li>
 *   <li>{@link AuditMdcRecordInterceptor} — поля MDC из заголовков записи, если заданы
 *       {@link #auditMdcKeys(List)}.</li>
 *   <li>{@code observationEnabled=true} — Spring Kafka восстанавливает traceId из
 *       {@code traceparent}-header'а в MDC на время обработки listener'ом.</li>
 * </ul>
 *
 * <h2>Использование</h2>
 * <pre>{@code
 * @Bean
 * public ConcurrentKafkaListenerContainerFactory<String, OrderEvent<Payload>>
 *         orderEventsListenerContainerFactory(
 *                 KafkaProperties props, JsonMapper jsonMapper, MeterRegistry meter,
 *                 DefaultErrorHandler errorHandler, Logbook logbook,
 *                 Tracer tracer, Propagator propagator) {
 *     JavaType type = jsonMapper.getTypeFactory().constructParametricType(
 *             OrderEvent.class, Payload.class);
 *     return new KafkaListenerContainerFactoryBuilder<OrderEvent<Payload>>()
 *             .valueType(type)
 *             .consumerProperties(props.buildConsumerProperties())
 *             .jsonMapper(jsonMapper)
 *             .meterRegistry(meter)
 *             .errorHandler(errorHandler)
 *             .logbook(logbook)
 *             .tracer(tracer)
 *             .propagator(propagator)
 *             .build();
 * }
 * }</pre>
 *
 * <p>Свойства консьюмера передаются готовым {@code Map<String, Object>}
 * (обычно {@code KafkaProperties.buildConsumerProperties()}), чтобы библиотеке
 * не нужна была зависимость от {@code spring-boot-kafka}.
 */
// PMD.CloseResource: JacksonJsonDeserializer хранится внутри
// DefaultKafkaConsumerFactory как Spring-managed bean — закрывается контейнером
// при shutdown'е.
@SuppressWarnings("PMD.CloseResource")
public final class KafkaListenerContainerFactoryBuilder<V> {

    private JavaType valueType;
    private Map<String, Object> consumerProperties;
    private JsonMapper jsonMapper;
    private MeterRegistry meterRegistry;
    private DefaultErrorHandler errorHandler;
    private Logbook logbook;
    private Tracer tracer;
    private Propagator propagator;
    private SecretMaskingScanner secretMaskingScanner;
    /**
     * Опциональный override ack-режима для container.properties. По умолчанию Spring Kafka
     * использует {@link ContainerProperties.AckMode#BATCH}. Для {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE}
     * listener должен принимать {@code org.springframework.kafka.support.Acknowledgment} и вызывать
     * {@code .acknowledge()} после фактического завершения обработки (в т. ч. из async-callback'а).
     */
    private ContainerProperties.AckMode ackMode;
    /**
     * Опциональный override {@link ContainerProperties.AssignmentCommitOption}. Default Spring Kafka
     * ({@code LATEST_ONLY_NO_TX}) коммитит стартовую позицию на assign'е при
     * {@code auto.offset.reset=latest} — даже если listener никогда не ack'ает. Для broadcast-групп
     * с per-JVM group.id нужен {@code NEVER}, иначе группа-призрак остаётся в {@code __consumer_offsets}.
     */
    private ContainerProperties.AssignmentCommitOption assignmentCommitOption;
    /**
     * По умолчанию контейнер регистрирует Micrometer Observation — на каждом record'е стартует
     * span ({@code kafka receive}) и восстанавливается traceId из {@code traceparent}-header'а.
     * Для тривиальных listener'ов с высоким QPS (опрос, heartbeat-потоки) это
     * замусоривает trace-backend — устанавливай {@code false}, чтобы выключить.
     */
    private boolean observationEnabled = true;
    /**
     * Опциональный rebalance-listener: для случаев, когда нужно реагировать на assign/revoke
     * партиций (seedить tracker'ы endOffsets'ами, sync'ить in-memory state с broker'ом и т. п.).
     */
    private ConsumerAwareRebalanceListener rebalanceListener;
    private List<String> auditMdcKeys = List.of();

    public KafkaListenerContainerFactoryBuilder<V> valueType(JavaType valueType) {
        this.valueType = valueType;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> consumerProperties(Map<String, Object> consumerProperties) {
        this.consumerProperties = consumerProperties;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> jsonMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> errorHandler(DefaultErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> logbook(Logbook logbook) {
        this.logbook = logbook;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> tracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> propagator(Propagator propagator) {
        this.propagator = propagator;
        return this;
    }

    public KafkaListenerContainerFactoryBuilder<V> secretMaskingScanner(SecretMaskingScanner scanner) {
        this.secretMaskingScanner = scanner;
        return this;
    }

    /**
     * Опционально: задать ack-режим контейнера (default — Spring Kafka default,
     * {@link ContainerProperties.AckMode#BATCH}). Для async-обработки с manual ack
     * нужен {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} + listener-метод
     * принимающий {@code Acknowledgment} параметр.
     */
    public KafkaListenerContainerFactoryBuilder<V> ackMode(ContainerProperties.AckMode ackMode) {
        this.ackMode = ackMode;
        return this;
    }

    /**
     * Опционально: политика коммита стартовой позиции на assign'е (см. поле
     * {@link #assignmentCommitOption}).
     */
    public KafkaListenerContainerFactoryBuilder<V> assignmentCommitOption(
            ContainerProperties.AssignmentCommitOption assignmentCommitOption) {
        this.assignmentCommitOption = assignmentCommitOption;
        return this;
    }

    /**
     * Включить/выключить Micrometer Observation на контейнере. Default — {@code true}
     * (восстановление traceId из {@code traceparent}-header'а). Установи {@code false}
     * для тривиальных listener'ов с высоким QPS, где per-record span замусоривает backend.
     */
    public KafkaListenerContainerFactoryBuilder<V> observationEnabled(boolean observationEnabled) {
        this.observationEnabled = observationEnabled;
        return this;
    }

    /**
     * Опционально: rebalance-listener для assign/revoke событий партиций.
     */
    public KafkaListenerContainerFactoryBuilder<V> rebalanceListener(ConsumerAwareRebalanceListener rebalanceListener) {
        this.rebalanceListener = rebalanceListener;
        return this;
    }

    /**
     * Опционально: поля MDC, которые восстанавливаются из одноимённых заголовков записи на время
     * её обработки (пара к {@link KafkaTemplateBuilder#auditMdcKeys(List)}). По умолчанию — нет.
     */
    public KafkaListenerContainerFactoryBuilder<V> auditMdcKeys(List<String> auditMdcKeys) {
        this.auditMdcKeys = List.copyOf(auditMdcKeys);
        return this;
    }

    public ConcurrentKafkaListenerContainerFactory<String, V> build() {
        Objects.requireNonNull(valueType, "valueType is required");
        Objects.requireNonNull(consumerProperties, "consumerProperties is required");
        Objects.requireNonNull(jsonMapper, "jsonMapper is required");
        Objects.requireNonNull(meterRegistry, "meterRegistry is required");
        Objects.requireNonNull(errorHandler, "errorHandler is required");
        Objects.requireNonNull(logbook, "logbook is required");
        Objects.requireNonNull(tracer, "tracer is required");
        Objects.requireNonNull(propagator, "propagator is required");

        ConcurrentKafkaListenerContainerFactory<String, V> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(buildConsumerFactory());
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setObservationEnabled(observationEnabled);
        if (ackMode != null) {
            factory.getContainerProperties().setAckMode(ackMode);
        }
        if (assignmentCommitOption != null) {
            factory.getContainerProperties().setAssignmentCommitOption(assignmentCommitOption);
        }
        if (rebalanceListener != null) {
            factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener);
        }
        SecretMaskingScanner scanner = secretMaskingScanner != null ? secretMaskingScanner : SecretMaskingScanner.NOOP;
        LogbookKafkaRecordInterceptor<String, V> logbookInterceptor = new LogbookKafkaRecordInterceptor<>(
                logbook, null, KafkaBodyMode.BODY, tracer, propagator, scanner);

        if (auditMdcKeys.isEmpty()) {
            factory.setRecordInterceptor(logbookInterceptor);
        } else {
            // AuditMdcRecordInterceptor первым — кладёт поля из заголовков в MDC до того, как Logbook
            // пишет consume-событие, и до самого listener'а (CompositeRecordInterceptor сохраняет
            // порядок). Так аудит-поля видны во всех логах обработки записи.
            factory.setRecordInterceptor(new CompositeRecordInterceptor<>(
                    new AuditMdcRecordInterceptor<>(auditMdcKeys), logbookInterceptor));
        }
        return factory;
    }

    private ConsumerFactory<String, V> buildConsumerFactory() {
        JacksonJsonDeserializer<V> jacksonDeserializer = new JacksonJsonDeserializer<>(valueType, jsonMapper);
        jacksonDeserializer.ignoreTypeHeaders();
        Deserializer<V> valueDeserializer = new ErrorHandlingDeserializer<>(
                new SaverForLogPayload<>(jacksonDeserializer));
        DefaultKafkaConsumerFactory<String, V> factory = new DefaultKafkaConsumerFactory<>(
                consumerProperties, new StringDeserializer(), valueDeserializer);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }
}
