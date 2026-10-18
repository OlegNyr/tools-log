package io.github.olegnyr.toolslog.boot;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import io.github.olegnyr.toolslog.logback.JacksonStructuredLogEncoder;
import io.github.olegnyr.toolslog.logback.ServiceInfo;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.StandardStackTracePrinter;
import org.springframework.core.env.Environment;

import java.util.function.Function;

/**
 * {@link JacksonStructuredLogEncoder} для Spring Boot: поля сервиса и pid — из {@code Environment},
 * стектрейс — принтером Spring Boot (корневая причина последней).
 *
 * <p>Поля сервиса берутся по старшинству: свойства encoder'а в {@code logback-spring.xml}, затем
 * {@code logging.structured.ecs.service.*} (как у структурных логов Spring Boot), затем
 * {@code spring.application.name} / {@code spring.application.version}. {@code Environment} в
 * контекст Logback кладёт система логирования Spring Boot; без него encoder работает как базовый.
 *
 * <pre>{@code
 * <encoder class="io.github.olegnyr.toolslog.boot.SpringStructuredLogEncoder"/>
 * }</pre>
 */
public class SpringStructuredLogEncoder extends JacksonStructuredLogEncoder {

    /** Префикс полей сервиса — тот же, что у структурных логов ECS в Spring Boot. */
    static final String SERVICE_PREFIX = "logging.structured.ecs.service";

    private static final StackTracePrinter STACK_TRACE_PRINTER = StandardStackTracePrinter.rootLast();

    @Override
    protected ServiceInfo resolveServiceInfo(ServiceInfo configured) {
        Environment environment = environment();
        if (environment == null) {
            return configured;
        }
        ServiceInfo bound = Binder.get(environment).bind(SERVICE_PREFIX, ServiceInfo.class).orElse(ServiceInfo.NONE);
        ServiceInfo application = ServiceInfo.builder()
                .name(environment.getProperty("spring.application.name"))
                .version(environment.getProperty("spring.application.version"))
                .build();
        return configured.orElse(bound).orElse(application);
    }

    @Override
    protected Long resolvePid() {
        Environment environment = environment();
        Long pid = environment == null ? null : environment.getProperty("spring.application.pid", Long.class);
        return pid == null ? super.resolvePid() : pid;
    }

    @Override
    protected Function<ILoggingEvent, String> stackTraceRenderer(ThrowableProxyConverter converter) {
        return event -> event.getThrowableProxy() instanceof ThrowableProxy proxy
                ? STACK_TRACE_PRINTER.printStackTraceToString(proxy.getThrowable())
                : converter.convert(event);
    }

    private Environment environment() {
        return getContext() == null ? null : (Environment) getContext().getObject(Environment.class.getName());
    }
}
