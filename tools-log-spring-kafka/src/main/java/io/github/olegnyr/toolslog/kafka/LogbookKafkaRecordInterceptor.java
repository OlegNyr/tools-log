package io.github.olegnyr.toolslog.kafka;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.With;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.listener.RecordInterceptor;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Logbook.ResponseProcessingStage;
import org.zalando.logbook.Strategy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Kafka {@link RecordInterceptor}, зеркалирующий consume-операции в Logbook
 * как HTTP-запросы. На {@code intercept} пишется request-стадия и сохраняется
 * pending-stage в TTL-кеш; {@code success} / {@code failure} достают её по UUID
 * из record-header'а и пишут response-стадию.
 *
 * <p>Pending-stages хранятся в Caffeine с {@link #STAGE_TTL} — если ни success,
 * ни failure не вызовется (rebalance, container shutdown посреди обработки),
 * entry автоматически выселится. Без TTL обычный {@code ConcurrentHashMap}
 * приводил бы к утечке памяти под нагрузкой.
 *
 * <p>Имя header'а — {@code <ResponseProcessingStage FQN>-<instance-UUID>}, чтобы
 * разные инстансы не конфликтовали даже на одной записи. {@link KafkaConsumerHttpRequest}
 * фильтрует все header'ы с префиксом {@link #STAGE_HEADER_PREFIX} и не пишет их в логи.
 *
 * <h2>traceId в request-логе consumer'а</h2>
 * Spring Kafka вызывает {@code RecordInterceptor.intercept} ДО старта Observation-обёртки
 * listener'а — в этот момент MDC ещё пуст, и без активного span'а traceId не попадает
 * в request-эвент. Чтобы это починить, мы используем тот же {@link Propagator},
 * что Spring Boot autoconfig'ит под формат
 * {@code management.tracing.propagation.type} (B3 / W3C), и стартуем короткий
 * CONSUMER-span'а на время записи request-стадии.
 *
 * <p>Span извлекается из {@code record.headers()} — если producer уже инжектил
 * trace context, наш span станет потомком оригинального. Если headers пусты или
 * Tracer/Propagator подменены на NOOP — span будет no-op, MDC не заполнится, но
 * лог всё равно пишется (старая семантика сохраняется).
 *
 * <p>Listener-side observation Spring'a продолжит работать поверх и создаст
 * полноценный consume-span; наш короткий span — это лишь scope для MDC во
 * время request-эвента Logbook'а.
 */
public class LogbookKafkaRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    /**
     * Префикс служебного header'а — используется {@link KafkaConsumerHttpRequest#getHeaders}
     * чтобы исключить наш UUID-маркер из выходного лога.
     */
    static final String STAGE_HEADER_PREFIX = ResponseProcessingStage.class.getName();

    /**
     * Наибольшая длина тела записи (в символах), которое сканируется на секреты. Тело длиннее
     * пишется в лог без сканирования: regex по мегабайтному сообщению на каждой записи дороже, чем
     * польза, — тот же порог, что у HTTP-фильтра маскирования.
     */
    public static final int MAX_SCAN_LENGTH = 64 * 1024;

    private static final Duration STAGE_TTL = Duration.ofMinutes(2);
    private static final long STAGE_MAX_SIZE = 100_000L;

    private final String responseProcessingStageName = STAGE_HEADER_PREFIX + "-" + UUID.randomUUID();

    private final Cache<String, ResponseProcessingStage> processingStageCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(STAGE_TTL)
                    .maximumSize(STAGE_MAX_SIZE)
                    .build();

    private final Logbook logbook;
    private final Strategy strategy;
    @With
    private final KafkaBodyMode bodyMode;
    /**
     * Tracer из контекста Spring (его даёт tracing-мост сервиса) для активации scope при записи
     * request-стадии — благодаря этому traceId попадает в MDC. {@link Tracer#NOOP}
     * для тестов — наш span тогда не создаётся.
     */
    private final Tracer tracer;
    /**
     * Propagator из Spring DI — формат headers (B3 / W3C) определяется
     * {@code management.tracing.propagation.type}. {@link Propagator#NOOP} —
     * extract вернёт пустой builder, span будет no-op.
     */
    private final Propagator propagator;
    /**
     * Сканер секретов: прогоняем тело consume-записи (event JSON с возможным PAN) по матчерам и
     * регистрируем найденное в {@link SecretMaskingRegistry} ДО записи Logbook'ом — тогда тело
     * записи в логе маскируется. На consumer-потоке HTTP-фильтр не работает, поэтому маскируем здесь.
     */
    private final SecretMaskingScanner secretMaskingScanner;

    /**
     * Getter для Kafka-headers — последний заголовок с указанным именем как UTF-8 строка.
     * Last-wins на случай, если producer случайно добавил дубликат traceparent'а.
     */
    private static final Propagator.Getter<Headers> HEADERS_GETTER = (carrier, key) -> {
        Header h = carrier.lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    };

    /**
     * Основной конструктор.
     *
     * @param logbook              Logbook, в который пишутся обмены
     * @param strategy             стратегия Logbook; {@code null} — стратегия самого Logbook
     * @param bodyMode             пишется ли значение записи телом
     * @param tracer               трассировщик; {@link Tracer#NOOP} — без span'а и traceId
     * @param propagator           формат trace-заголовков; {@link Propagator#NOOP} — без извлечения
     * @param secretMaskingScanner сканер секретов тела записи
     */
    public LogbookKafkaRecordInterceptor(Logbook logbook, Strategy strategy, KafkaBodyMode bodyMode, Tracer tracer,
                                         Propagator propagator, SecretMaskingScanner secretMaskingScanner) {
        this.logbook = logbook;
        this.strategy = strategy;
        this.bodyMode = bodyMode;
        this.tracer = tracer;
        this.propagator = propagator;
        this.secretMaskingScanner = secretMaskingScanner;
    }

    public LogbookKafkaRecordInterceptor() {
        this(Logbook.create());
    }

    public LogbookKafkaRecordInterceptor(Logbook logbook) {
        this(logbook, null);
    }

    public LogbookKafkaRecordInterceptor(Logbook logbook, Strategy strategy) {
        this(logbook, strategy, KafkaBodyMode.BODY);
    }

    public LogbookKafkaRecordInterceptor(Logbook logbook, Strategy strategy, KafkaBodyMode bodyMode) {
        this(logbook, strategy, bodyMode, Tracer.NOOP, Propagator.NOOP, SecretMaskingScanner.NOOP);
    }

    @Override
    public @Nullable ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        HttpRequest request = new KafkaConsumerHttpRequest<>(record,
                consumer.groupMetadata().groupId(),
                null,
                bodyMode);
        // Извлекаем trace context из headers тем же propagator'ом, что Spring
        // инжектит на producer-side. Если headers'ов нет / Propagator NOOP —
        // span будет no-op, MDC пустой, но Logbook всё равно отработает.
        Span span = propagator.extract(record.headers(), HEADERS_GETTER)
                .kind(Span.Kind.CONSUMER)
                .name("kafka receive " + record.topic())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            ResponseProcessingStage processing;
            try {
                // Сканируем ТОТ ЖЕ body, что залогирует Logbook (payload-snapshot из header'а либо
                // value записи — см. KafkaConsumerHttpRequest.withBody) и регистрируем секреты в реестр
                // ДО записи — тогда тело записи в логе маскируется. Чистим сразу после: дальше тело
                // записи в логах не всплывает. Большие тела не сканируем — как и в HTTP-фильтре.
                String body = request.withBody().getBodyAsString();
                if (body != null && body.length() <= MAX_SCAN_LENGTH) {
                    secretMaskingScanner.scanAndRegister(body);
                }
                processing = process(request).write();
            } catch (IOException e) {
                throw new UncheckedIOException("Logbook failed to process Kafka consume request", e);
            } finally {
                SecretMaskingRegistry.clear();
            }
            String stageId = UUID.randomUUID().toString();
            record.headers().add(responseProcessingStageName, stageId.getBytes(StandardCharsets.UTF_8));
            processingStageCache.put(stageId, processing);
            return record;
        } finally {
            span.end();
        }
    }

    @Override
    public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        processResponse(record, null);
    }

    @Override
    public void failure(ConsumerRecord<K, V> record, Exception exception, Consumer<K, V> consumer) {
        processResponse(record, exception);
    }

    private void processResponse(ConsumerRecord<K, V> record, Exception exception) {
        getStageId(record.headers()).ifPresent(stageId -> {
            // remove(...) на view от Caffeine — атомарный pop: defensive против
            // двойного вызова success/failure (а такое возможно при ошибке cleanup'а).
            ResponseProcessingStage stage = processingStageCache.asMap().remove(stageId);
            if (stage == null) {
                // Entry экспортирована Caffeine'ом по TTL или предыдущим вызовом.
                return;
            }
            // Spring Kafka вызывает success/failure ПОСЛЕ закрытия listener-observation,
            // а короткий span из intercept() уже завершён — без активного span'а response-эвент
            // писался бы без traceId. Восстанавливаем тот же scope из headers (traceparent ещё в
            // record'е), чтобы response нёс тот же traceId, что и request.
            Span span = propagator.extract(record.headers(), HEADERS_GETTER)
                    .kind(Span.Kind.CONSUMER)
                    .name("kafka receive " + record.topic())
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                stage.process(new KafkaConsumerHttpResponse(exception)).write();
            } catch (IOException e) {
                throw new UncheckedIOException("Logbook failed to process Kafka consume response", e);
            } finally {
                span.end();
            }
        });
    }

    private Optional<String> getStageId(Headers headers) {
        return Optional.ofNullable(headers.lastHeader(responseProcessingStageName))
                .map(Header::value)
                .map(value -> new String(value, StandardCharsets.UTF_8));
    }

    private Logbook.RequestWritingStage process(HttpRequest request) throws IOException {
        return strategy == null ? logbook.process(request) : logbook.process(request, strategy);
    }
}
