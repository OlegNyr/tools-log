package io.github.olegnyr.toolslog.kafka;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.Delegate;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.ProducerPostProcessor;
import org.springframework.util.ObjectUtils;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Logbook.ResponseProcessingStage;
import org.zalando.logbook.Strategy;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Логирует Kafka-операции продьюсера через Logbook (как HTTP-запросы) и
 * пробрасывает context (MDC, traceId) из вызывающего потока в callback,
 * который Kafka выполняет в worker-thread'е.
 *
 * <p>Регистрируется как {@link ProducerPostProcessor} через Spring Kafka — оборачивает
 * любой бин {@link Producer}. На каждый {@code send}:
 * <ol>
 *   <li>пишет {@link KafkaProducerHttpRequest} в Logbook (request-stage);</li>
 *   <li>захватывает {@link ContextSnapshot} из текущего треда;</li>
 *   <li>в onCompletion: восстанавливает context (через
 *       {@link io.github.olegnyr.toolslog.context.MdcThreadLocalAccessor}, зарегистрированный SPI'ем),
 *       пишет {@link KafkaProducerHttpResponse} (response-stage), вызывает userCallback;</li>
 *   <li>если {@code producer.send} бросил синхронно (full buffer / closed producer) —
 *       пишет response с exception и пробрасывает наверх, чтобы запись в логе не "висела"
 *       без статуса.</li>
 * </ol>
 *
 * <p>Конструкторы:
 * <ul>
 *   <li>{@link #LogbookContextKafkaProducerInterceptor()} — умолчания для тестов;</li>
 *   <li>основной 4-аргументный конструктор — порядок:
 *       {@code logbook, strategy, bodyMode, valueToString}.</li>
 * </ul>
 * Прежние «удобные» 3-аргументные перегрузки убраны — слишком легко перепутать порядок
 * между {@code strategy} и {@code bodyMode}; используйте основной конструктор
 * с явным {@code null} для {@code strategy}, если хотите дефолтный pipeline Logbook'а.
 */
public class LogbookContextKafkaProducerInterceptor<K, V> implements ProducerPostProcessor<K, V> {

    private final Logbook logbook;
    private final Strategy strategy;
    private final KafkaBodyMode bodyMode;
    private final Function<V, String> valueToString;

    /**
     * Основной конструктор.
     *
     * @param logbook       Logbook, в который пишутся обмены
     * @param strategy      стратегия Logbook; {@code null} — стратегия самого Logbook
     * @param bodyMode      пишется ли значение записи телом
     * @param valueToString значение записи → текст тела; ошибка или {@code null} — тела нет
     */
    public LogbookContextKafkaProducerInterceptor(Logbook logbook, Strategy strategy, KafkaBodyMode bodyMode,
                                                  Function<V, String> valueToString) {
        this.logbook = logbook;
        this.strategy = strategy;
        this.bodyMode = bodyMode;
        this.valueToString = valueToString;
    }

    public LogbookContextKafkaProducerInterceptor() {
        this(Logbook.create(), null, KafkaBodyMode.BODY, ObjectUtils::getDisplayString);
    }

    @Override
    public Producer<K, V> apply(Producer<K, V> kvProducer) {
        return new LoggerProducer<>(kvProducer, strategy, bodyMode, logbook, valueToString);
    }

    @AllArgsConstructor
    private static class LoggerProducer<K, V> implements Producer<K, V> {

        private static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY =
                ContextSnapshotFactory.builder().build();

        @Delegate
        private final Producer<K, V> producer;
        private final Strategy strategy;
        private final KafkaBodyMode bodyMode;
        private final Logbook logbook;
        private final Function<V, String> valueToString;

        @Override
        public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
            return this.send(record, null);
        }

        @SneakyThrows
        @Override
        public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback userCallback) {
            KafkaProducerHttpRequest<K, V> httpRequest =
                    new KafkaProducerHttpRequest<>(record, null, bodyMode, valueToString);
            ResponseProcessingStage processingStage = process(httpRequest).write();
            ContextSnapshot contextSnapshot = CONTEXT_SNAPSHOT_FACTORY.captureAll();

            try {
                return producer.send(record, new Callback() {
                    @Override
                    @SneakyThrows
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        try (var ignore = contextSnapshot.setThreadLocals()) {
                            processingStage
                                    .process(new KafkaProducerHttpResponse(metadata, exception))
                                    .write();
                            if (userCallback != null) {
                                userCallback.onCompletion(metadata, exception);
                            }
                        }
                    }
                });
            } catch (RuntimeException syncThrow) {
                // producer.send бросил синхронно (full buffer, closed producer и т.п.) —
                // callback не будет вызван, поэтому записываем response с 500 здесь же,
                // чтобы request-event не "висел" без status'а в логе.
                try (var ignore = contextSnapshot.setThreadLocals()) {
                    processingStage
                            .process(new KafkaProducerHttpResponse(null, syncThrow))
                            .write();
                }
                throw syncThrow;
            }
        }

        private Logbook.RequestWritingStage process(HttpRequest request) throws IOException {
            return strategy == null ? logbook.process(request) : logbook.process(request, strategy);
        }
    }
}
