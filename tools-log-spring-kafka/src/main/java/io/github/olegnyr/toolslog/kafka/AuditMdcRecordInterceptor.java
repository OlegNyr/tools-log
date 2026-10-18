package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Восстанавливает аудит-поля из заголовков Kafka-записи в MDC на время её обработки. Пара к
 * {@link AuditHeaderProducerInterceptor}, который пишет эти заголовки на стороне продьюсера.
 *
 * <p>{@code intercept} ставит MDC до listener'а и до записи consume-события Logbook'ом, если этот
 * перехватчик стоит первым в {@code CompositeRecordInterceptor}. {@code afterRecord} — после
 * success/failure — снимает поля, чтобы они не перешли на следующую запись. Поля не должны входить
 * в {@code management.tracing.baggage.correlation.fields}: иначе tracing-мост перезапишет их при
 * открытии observation listener'а.
 *
 * @param <K> тип ключа
 * @param <V> тип значения
 */
public class AuditMdcRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    private final List<String> mdcKeys;

    /**
     * Перехватчик для заданных полей.
     *
     * @param mdcKeys имена полей MDC — они же имена заголовков
     */
    public AuditMdcRecordInterceptor(List<String> mdcKeys) {
        this.mdcKeys = List.copyOf(mdcKeys);
    }

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        for (String key : mdcKeys) {
            Header header = record.headers().lastHeader(key);
            if (header != null) {
                MDC.put(key, new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        mdcKeys.forEach(MDC::remove);
    }
}
