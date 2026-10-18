package io.github.olegnyr.toolslog.kafka;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.Origin;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerHttpResponseTest {

    @Test
    void onSuccess_shouldEmit200() {
        KafkaConsumerHttpResponse response = new KafkaConsumerHttpResponse(null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getReasonPhrase()).isNull();
        assertThat(response.getOrigin())
                .as("consumer-response от listener'а наружу — LOCAL")
                .isEqualTo(Origin.LOCAL);
    }

    @Test
    void onException_shouldEmit500AndRootCause() {
        Exception ex = new RuntimeException("outer", new IllegalStateException("inner-root"));

        KafkaConsumerHttpResponse response = new KafkaConsumerHttpResponse(ex);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getReasonPhrase()).contains("inner-root");
    }
}
