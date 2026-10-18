package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import lombok.Setter;

import java.util.function.Function;

/**
 * Encoder Logback: каждое событие — одна строка JSON в формате, близком к ECS ({@link FormatterElastic}).
 *
 * <p>Поля сервиса задаются свойствами encoder'а в {@code logback.xml}; pid — текущего процесса,
 * стектрейс — конвертером Logback. Для Spring Boot есть наследник {@code SpringStructuredLogEncoder}
 * в {@code tools-log-spring-boot}: он берёт поля сервиса из {@code Environment}.
 *
 * <pre>{@code
 * <encoder class="io.github.olegnyr.toolslog.logback.JacksonStructuredLogEncoder">
 *   <name>orders</name>
 *   <version>1.4.0</version>
 * </encoder>
 * }</pre>
 */
public class JacksonStructuredLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];

    private final ThrowableProxyConverter throwableProxyConverter = new ThrowableProxyConverter();
    private FormatterElastic formatter;

    /** Система, к которой относится сервис. */
    @Setter
    private String system;

    /** Сервис внутри системы. */
    @Setter
    private String service;

    /** Источник событий. */
    @Setter
    private String reporter;

    /** Имя приложения. */
    @Setter
    private String name;

    /** Версия приложения. */
    @Setter
    private String version;

    /** Окружение (стенд). */
    @Setter
    private String environment;

    /** Имя узла. */
    @Setter
    private String nodeName;

    /** Коммит сборки. */
    @Setter
    private String commit;

    @Override
    public void start() {
        throwableProxyConverter.start();
        this.formatter = new FormatterElastic(resolveServiceInfo(configuredServiceInfo()), resolvePid(),
                stackTraceRenderer(throwableProxyConverter));
        super.start();
    }

    @Override
    public void stop() {
        throwableProxyConverter.stop();
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return this.formatter.formatAsBytes(event);
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    /**
     * Поля сервиса, заданные свойствами encoder'а.
     *
     * @return поля сервиса; незаданные — {@code null}
     */
    protected final ServiceInfo configuredServiceInfo() {
        return new ServiceInfo(system, service, reporter, name, version, environment, nodeName, commit);
    }

    /**
     * Итоговые поля сервиса. Наследник дополняет их из своего источника.
     *
     * @param configured поля из свойств encoder'а
     * @return поля сервиса для лога
     */
    protected ServiceInfo resolveServiceInfo(ServiceInfo configured) {
        return configured;
    }

    /**
     * Pid для поля {@code process.pid}.
     *
     * @return pid текущего процесса
     */
    protected Long resolvePid() {
        return ProcessHandle.current().pid();
    }

    /**
     * Печать стектрейса события с исключением.
     *
     * @param converter конвертер Logback, уже запущенный
     * @return стектрейс события
     */
    protected Function<ILoggingEvent, String> stackTraceRenderer(ThrowableProxyConverter converter) {
        return converter::convert;
    }
}
