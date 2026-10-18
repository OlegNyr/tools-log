package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.ByteBuffer;
import java.util.Map;

public class SaverForLogPayload<T> implements Deserializer<T> {

    public static final String HEADER_ATTRIBUTE_NAME = "attribute." + SaverForLogPayload.class.getName();
    private final Deserializer<T> delegate;

    public SaverForLogPayload(Deserializer<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T deserialize(String topic, byte[] bytes) {
        return delegate.deserialize(topic, bytes);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        delegate.configure(configs, isKey);
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        headers.add(HEADER_ATTRIBUTE_NAME, data);

        return delegate.deserialize(topic, headers, data);
    }

    @Override
    public T deserialize(String topic, Headers headers, ByteBuffer data) {
        if (data != null) {
            headers.add(HEADER_ATTRIBUTE_NAME, data.array());
        }
        return delegate.deserialize(topic, headers, data);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
