package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Копирует аудит-поля из MDC в заголовки Kafka-записи на стороне продьюсера. Пара к
 * {@link AuditMdcRecordInterceptor}: тот на стороне консьюмера читает заголовки обратно в MDC на
 * время обработки записи, и логи обработки несут «кто/откуда» наряду с traceId.
 *
 * <p>Явно через заголовки, а не через baggage трассировки: baggage доезжает до Kafka-заголовков не
 * во всех связках tracing-моста и observation, а заголовки — детерминированно.
 *
 * <p>Имена полей — свойство продьюсера {@value #MDC_KEYS_CONFIG} (список или строка через запятую);
 * {@link KafkaTemplateBuilder#auditMdcKeys(List)} ставит его сам. {@code onSend} выполняется на
 * потоке вызывающего {@code send}, где MDC уже заполнен. Заголовок пишется, только когда значение
 * есть в MDC и заголовка ещё нет — повторная публикация его не задваивает.
 *
 * <p>Kafka создаёт перехватчик рефлексией: нужен public-класс с public конструктором без аргументов.
 */
public class AuditHeaderProducerInterceptor implements ProducerInterceptor<Object, Object> {

    /** Свойство продьюсера со списком полей MDC. */
    public static final String MDC_KEYS_CONFIG = "toolslog.audit.mdc-keys";

    private List<String> mdcKeys = List.of();

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        for (String key : mdcKeys) {
            String value = MDC.get(key);
            if (value != null && record.headers().lastHeader(key) == null) {
                record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // Нужна только запись заголовков на send.
    }

    @Override
    public void close() {
        // Ресурсов нет.
    }

    @Override
    public void configure(Map<String, ?> configs) {
        this.mdcKeys = parseKeys(configs.get(MDC_KEYS_CONFIG));
    }

    static List<String> parseKeys(Object value) {
        return switch (value) {
            case null -> List.of();
            case Collection<?> keys -> keys.stream().map(String::valueOf).map(String::trim)
                    .filter(key -> !key.isEmpty()).toList();
            default -> Arrays.stream(String.valueOf(value).split(",")).map(String::trim)
                    .filter(key -> !key.isEmpty()).toList();
        };
    }
}
