package io.github.olegnyr.toolslog.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.zalando.logbook.Logbook;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaListenerContainerFactoryBuilderTest {

    private static KafkaListenerContainerFactoryBuilder<String> minimalBuilder() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        return new KafkaListenerContainerFactoryBuilder<String>()
                .valueType(jsonMapper.constructType(String.class))
                .consumerProperties(Map.of("bootstrap.servers", "localhost:9092"))
                .jsonMapper(jsonMapper)
                .meterRegistry(new SimpleMeterRegistry())
                .errorHandler(mock(DefaultErrorHandler.class))
                .logbook(mock(Logbook.class))
                .tracer(mock(Tracer.class))
                .propagator(mock(Propagator.class));
    }

    @Test
    void build_shouldKeepSpringKafkaDefaults_whenCommitOptionsNotSet() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = minimalBuilder().build();

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(factory.getContainerProperties().getAssignmentCommitOption())
                .isEqualTo(ContainerProperties.AssignmentCommitOption.LATEST_ONLY_NO_TX);
    }

    /**
     * Default Spring Kafka (LATEST_ONLY_NO_TX) коммитит стартовую позицию на assign'е при
     * auto.offset.reset=latest — даже если listener никогда не ack'ает. Для broadcast-групп
     * с per-JVM group.id это оставляет призрак в __consumer_offsets; опция должна доходить до
     * container properties.
     */
    @Test
    void build_shouldApplyAssignmentCommitOption_whenSet() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = minimalBuilder()
                .ackMode(ContainerProperties.AckMode.MANUAL)
                .assignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER)
                .build();

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(factory.getContainerProperties().getAssignmentCommitOption())
                .isEqualTo(ContainerProperties.AssignmentCommitOption.NEVER);
    }
}
