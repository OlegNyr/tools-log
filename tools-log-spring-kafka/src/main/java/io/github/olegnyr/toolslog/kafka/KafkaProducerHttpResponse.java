package io.github.olegnyr.toolslog.kafka;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;
import org.zalando.logbook.attributes.HttpAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class KafkaProducerHttpResponse implements HttpResponse {

    private final int status;
    private final String message;
    private final Map<String, Object> attributes;

    /**
     * Конструирует адаптер ответа для Logbook'а.
     *
     * <p>{@code metadata} может быть {@code null} — Spring Kafka гарантирует non-null
     * только при успешной отправке. Если send упал на сериализаторе или ProducerInterceptor'е,
     * сюда придёт {@code null} + exception. В этом случае атрибуты пустые, но запись
     * всё равно будет залогирована со статусом 500.
     */
    public KafkaProducerHttpResponse(RecordMetadata metadata, Exception exception) {
        this.status = exception == null ? 200 : 500;
        this.message = exception == null ? null : ExceptionUtils.getRootCauseMessage(exception);
        this.attributes = buildAttributes(metadata);
    }

    private static Map<String, Object> buildAttributes(RecordMetadata metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> attrs = new HashMap<>(3);
        attrs.put("topic", metadata.topic());
        attrs.put("partition", metadata.partition());
        attrs.put("offset", metadata.offset());
        return attrs;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public HttpResponse withBody() throws IOException {
        return this;
    }

    @Override
    public HttpResponse withoutBody() {
        return this;
    }

    @Override
    public String getReasonPhrase() {
        return message;
    }

    @Override
    public String getProtocolVersion() {
        return null;
    }

    @Override
    public Origin getOrigin() {
        return Origin.REMOTE;
    }

    @Override
    public HttpHeaders getHeaders() {
        return HttpHeaders.empty();
    }

    @Override
    public byte[] getBody() throws IOException {
        return new byte[0];
    }

    @Override
    public HttpAttributes getAttributes() {
        return new HttpAttributes(attributes);
    }
}
