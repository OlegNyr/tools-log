package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.ObjectUtils;
import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Origin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static io.github.olegnyr.toolslog.kafka.SaverForLogPayload.HEADER_ATTRIBUTE_NAME;

public class KafkaConsumerHttpRequest<K, V> implements HttpRequest {
    private final ConsumerRecord<K, V> record;
    private final String groupId;
    private final String value;
    private final KafkaBodyMode bodyMode;

    public KafkaConsumerHttpRequest(ConsumerRecord<K, V> record, String groupId, String value,
                                    KafkaBodyMode bodyMode) {
        this.record = record;
        this.groupId = groupId;
        this.value = value;
        this.bodyMode = bodyMode;
    }

    @Override
    public String getRemote() {
        return "";
    }

    @Override
    public String getMethod() {
        return "";
    }

    @Override
    public String getProtocolVersion() {
        return "";
    }

    @Override
    public String getScheme() {
        return "kafka";
    }

    @Override
    public String getHost() {
        return record.topic();
    }

    @Override
    public Optional<Integer> getPort() {
        return Optional.empty();
    }

    @Override
    public String getPath() {
        return record.topic();
    }

    @Override
    public String getQuery() {
        return KafkaUtils.format(record) + "/" + groupId;
    }

    @Override
    public HttpRequest withBody() throws IOException {
        if (bodyMode == KafkaBodyMode.BODY) {
            String payload = Optional
                    .ofNullable(record.headers().lastHeader(HEADER_ATTRIBUTE_NAME))
                    .map(Header::value)
                    .map(String::new)
                    .orElseGet(() -> ObjectUtils.getDisplayString(record.value()));
            return new KafkaConsumerHttpRequest<>(record, groupId, payload, bodyMode);
        } else {
            return withoutBody();
        }
    }

    @Override
    public HttpRequest withoutBody() {
        return this;
    }

    @Override
    public Origin getOrigin() {
        return Origin.REMOTE;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = HttpHeaders.empty();
        for (Header header : record.headers()) {
            // Служебные header'ы Logbook'овской цепочки — payload-snapshot от
            // SaverForLogPayload и UUID-маркер от LogbookKafkaRecordInterceptor —
            // не должны попадать в исходящий лог как обычные заголовки.
            if (InternalHeaders.isInternal(header.key())) {
                continue;
            }
            headers = headers.update(header.key(), getDisplayString(header.value()));
        }
        headers = headers.update("key", ObjectUtils.getDisplayString(record.key()));
        return headers;
    }

    private static String getDisplayString(byte[] value) {
        if (value == null) {
            return "";
        }
        try {
            return new String(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ObjectUtils.getDisplayString(value);
        }
    }

    @Override
    public byte[] getBody() throws IOException {
        if (bodyMode == KafkaBodyMode.BODY) {
            return value.getBytes(getCharset());
        } else {
            return new byte[0];
        }
    }

    @Override
    public String getBodyAsString() throws IOException {
        if (bodyMode == KafkaBodyMode.BODY) {
            return value;
        } else {
            return "";
        }
    }
}
