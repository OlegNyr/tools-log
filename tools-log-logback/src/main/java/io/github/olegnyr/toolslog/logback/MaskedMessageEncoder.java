package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Delegate;

/**
 * Обёртка над encoder'ом Logback: перед кодированием заменяет в тексте сообщения секреты,
 * зарегистрированные в {@link SecretMaskingRegistry} на текущем потоке.
 *
 * <pre>{@code
 * <encoder class="io.github.olegnyr.toolslog.logback.MaskedMessageEncoder">
 *   <encoder class="io.github.olegnyr.toolslog.logback.JacksonStructuredLogEncoder"/>
 * </encoder>
 * }</pre>
 */
public class MaskedMessageEncoder extends EncoderBase<ILoggingEvent> {

    @Getter
    @Setter
    private Encoder<ILoggingEvent> encoder;

    @Override
    public boolean isStarted() {
        return encoder.isStarted();
    }

    @Override
    public void start() {
        // Контекст — до старта: вложенный encoder читает его при старте (Spring-encoder берёт
        // оттуда Environment). Joran обычно сам выставляет контекст и стартует вложенный компонент.
        if (encoder.getContext() == null) {
            encoder.setContext(context);
        }
        if (!encoder.isStarted()) {
            encoder.start();
        }
    }

    @Override
    public void stop() {
        encoder.stop();
    }

    @Override
    public byte[] headerBytes() {
        return encoder.headerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        ILoggingEvent eventMask = new LoggingEventMask(event);
        return encoder.encode(eventMask);
    }

    @Override
    public byte[] footerBytes() {
        return encoder.footerBytes();
    }

    public static class LoggingEventMask implements ILoggingEvent {

        @Delegate
        private final ILoggingEvent delegate;

        private String maskFormattedMessage;

        public LoggingEventMask(ILoggingEvent delegate) {
            this.delegate = delegate;
        }

        // MDC и key-value пары НЕ маскируем (делегируются как есть): секреты попадают в тело,
        // которое логируется как message; заголовки маскирует Logbook HeaderFilter, в MDC секретов нет.
        @Override
        public String getFormattedMessage() {
            if (maskFormattedMessage != null) {
                return maskFormattedMessage;
            }
            maskFormattedMessage = SecretMaskingRegistry.maskAll(delegate.getFormattedMessage());
            return maskFormattedMessage;
        }
    }
}
