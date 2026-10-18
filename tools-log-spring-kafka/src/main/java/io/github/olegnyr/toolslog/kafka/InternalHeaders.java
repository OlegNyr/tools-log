package io.github.olegnyr.toolslog.kafka;

/**
 * Служебные заголовки Kafka-записи, которые ставит сама библиотека и которые не пишутся в лог.
 *
 * <p>Это снимок тела от {@link SaverForLogPayload} и маркер стадии от
 * {@link LogbookKafkaRecordInterceptor}. Проверку зовут оба адаптера — потребителя и продьюсера:
 * запись, переотправленная из потребителя (DLT, forward), несёт эти заголовки дальше.
 */
final class InternalHeaders {

    private InternalHeaders() {
    }

    /**
     * Проверяет, служебный ли заголовок.
     *
     * @param key имя заголовка, может быть {@code null}
     * @return {@code true}, если заголовок ставит библиотека и в лог он не пишется
     */
    static boolean isInternal(String key) {
        return key != null
                && (key.startsWith(SaverForLogPayload.HEADER_ATTRIBUTE_NAME)
                || key.startsWith(LogbookKafkaRecordInterceptor.STAGE_HEADER_PREFIX));
    }
}
