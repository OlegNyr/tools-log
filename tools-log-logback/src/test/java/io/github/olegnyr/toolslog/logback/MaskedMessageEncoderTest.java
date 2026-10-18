package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedMessageEncoderTest {

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    @Test
    @DisplayName("Вложенный encoder получает контекст до старта")
    void start_shouldPassContextToNestedEncoder_beforeStartingIt() {
        LoggerContext context = new LoggerContext();
        ContextCapturingEncoder nested = new ContextCapturingEncoder();
        MaskedMessageEncoder encoder = new MaskedMessageEncoder();
        encoder.setContext(context);
        encoder.setEncoder(nested);

        encoder.start();

        assertThat(nested.contextAtStart).isSameAs(context);
    }

    @Test
    @DisplayName("Зарегистрированный секрет заменяется в сообщении")
    void encode_shouldMaskRegisteredSecret_whenMessageContainsIt() {
        LoggerContext context = new LoggerContext();
        MaskedMessageEncoder encoder = new MaskedMessageEncoder();
        encoder.setContext(context);
        encoder.setEncoder(new JacksonStructuredLogEncoder());
        encoder.start();
        SecretMaskingRegistry.register("4111111111111111", "************1111");
        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("logger"), Level.INFO,
                "card 4111111111111111", null, null);
        event.setMDCPropertyMap(Map.of());

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(json).contains("card ************1111").doesNotContain("4111111111111111");
    }

    private static final class ContextCapturingEncoder extends JacksonStructuredLogEncoder {

        private Object contextAtStart;

        @Override
        public void start() {
            contextAtStart = getContext();
            super.start();
        }
    }
}
