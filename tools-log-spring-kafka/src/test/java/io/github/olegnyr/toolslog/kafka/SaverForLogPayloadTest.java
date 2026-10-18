package io.github.olegnyr.toolslog.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.olegnyr.toolslog.kafka.SaverForLogPayload.HEADER_ATTRIBUTE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SaverForLogPayload} — тонкая Deserializer-обёртка, которая дополнительно кладёт
 * raw-payload в Kafka header под ключом {@link SaverForLogPayload#HEADER_ATTRIBUTE_NAME}.
 *
 * <p>Тесты документируют:
 * <ul>
 *   <li>прозрачный делегат для байтового и ByteBuffer-варианта;</li>
 *   <li>копирование тела в headers (чтобы Logbook'овский consumer-request мог отдать
 *       wire-format'ный payload, не зависящий от типизированного результата делегата);</li>
 *   <li>обработка null-data (tombstone в compact-топиках);</li>
 *   <li>пробрасывание {@code configure}/{@code close} в делегата.</li>
 * </ul>
 */
class SaverForLogPayloadTest {

    @Test
    void deserializeBytes_shouldDelegateAndAddRawPayloadHeader() {
        Deserializer<String> delegate = (topic, bytes) -> bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        SaverForLogPayload<String> saver = new SaverForLogPayload<>(delegate);
        Headers headers = new RecordHeaders();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        String result = saver.deserialize("t", headers, data);

        assertThat(result).isEqualTo("hello");
        Header h = headers.lastHeader(HEADER_ATTRIBUTE_NAME);
        assertThat(h).as("Header с raw payload должен быть добавлен").isNotNull();
        assertThat(new String(h.value(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void deserializeByteBuffer_shouldCopyBufferArrayIntoHeader() {
        Deserializer<String> delegate = new Deserializer<>() {
            @Override
            public String deserialize(String topic, byte[] bytes) {
                return null; // not used; ByteBuffer overload вызовется
            }

            @Override
            public String deserialize(String topic, Headers headers, ByteBuffer data) {
                return data == null ? null : StandardCharsets.UTF_8.decode(data.duplicate()).toString();
            }
        };
        SaverForLogPayload<String> saver = new SaverForLogPayload<>(delegate);
        Headers headers = new RecordHeaders();
        ByteBuffer buf = ByteBuffer.wrap("payload-bb".getBytes(StandardCharsets.UTF_8));

        String result = saver.deserialize("t", headers, buf);

        assertThat(result).isEqualTo("payload-bb");
        Header h = headers.lastHeader(HEADER_ATTRIBUTE_NAME);
        assertThat(h).isNotNull();
        assertThat(new String(h.value(), StandardCharsets.UTF_8)).isEqualTo("payload-bb");
    }

    @Test
    void deserializeByteBuffer_shouldNotAddHeader_whenDataNull() {
        Deserializer<String> delegate = new Deserializer<>() {
            @Override
            public String deserialize(String topic, byte[] bytes) {
                return null;
            }

            @Override
            public String deserialize(String topic, Headers headers, ByteBuffer data) {
                return null;
            }
        };
        SaverForLogPayload<String> saver = new SaverForLogPayload<>(delegate);
        Headers headers = new RecordHeaders();

        String result = saver.deserialize("t", headers, (ByteBuffer) null);

        assertThat(result).isNull();
        // Tombstone-кейс: payload-header класть некуда — данные отсутствуют.
        assertThat(headers.lastHeader(HEADER_ATTRIBUTE_NAME)).isNull();
    }

    @Test
    void configureAndClose_shouldDelegate() {
        AtomicBoolean configured = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        Deserializer<String> delegate = new Deserializer<>() {
            @Override
            public String deserialize(String topic, byte[] bytes) {
                return "x";
            }

            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
                configured.set(true);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        SaverForLogPayload<String> saver = new SaverForLogPayload<>(delegate);

        saver.configure(Map.of(), false);
        saver.close();

        assertThat(configured).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void deserializeBytesOnly_shouldDelegate_withoutHeadersOverload() {
        // Перегрузка (topic, byte[]) без headers — старый Kafka API. Должна делегировать,
        // но никаких side-effect'ов в headers (потому что headers недоступны).
        Deserializer<String> delegate = (topic, bytes) -> "decoded:" + new String(bytes, StandardCharsets.UTF_8);
        SaverForLogPayload<String> saver = new SaverForLogPayload<>(delegate);

        String result = saver.deserialize("t", "raw".getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEqualTo("decoded:raw");
    }
}
