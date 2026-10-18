package io.github.olegnyr.toolslog.kafka;

/**
 * Пишется ли значение Kafka-записи телом Logbook-обмена.
 *
 * <p>Своё перечисление, а не {@code KafkaBodyMode} из {@code logbook-servlet}: Kafka-модулю
 * servlet-зависимость не нужна.
 */
public enum KafkaBodyMode {

    /** Значение записи — тело запроса в логе. */
    BODY,

    /** Тело не пишется: в логе только топик, ключ и заголовки. */
    OFF
}
