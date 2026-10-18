package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.ArrayList;
import java.util.List;

/**
 * Глушит {@code DEBUG}/{@code TRACE} у логгеров, через которые проходят персональные данные,
 * <b>независимо от настроенного уровня</b>.
 *
 * <p>Зачем поверх {@code logging.level} в {@code application.yml}: уровень логгера — не барьер,
 * а значение по умолчанию. Actuator-эндпоинт {@code /actuator/loggers}, если он открыт, меняет его
 * в рантайме одним POST'ом, и настройка из конфига перестаёт действовать. Для ПДн это не
 * теоретический риск: на {@code debug} ORM и SQL-мапперы печатают запросы <b>вместе со значениями
 * параметров</b> — телефоном, ФИО, датой рождения, — а маскирование по шаблонам такие значения
 * не узнаёт.
 *
 * <p>TurboFilter выполняется до проверки уровня и его нельзя переопределить снаружи: событие
 * отбрасывается, даже если кто-то выставил логгеру {@code DEBUG}. Диагностику это не ломает —
 * {@code INFO} и выше проходят как обычно — тексты приложения на этих уровнях не должны содержать
 * ПДн (например, за счёт собственных {@code toString} у моделей с персональными данными).
 *
 * <p>Конфигурируется в {@code logback-spring.xml}:
 * <pre>{@code
 * <turboFilter class="io.github.olegnyr.toolslog.logback.PiiLoggerGuard">
 *   <logger>com.example.orders.persistence.customer</logger>
 * </turboFilter>
 * }</pre>
 */
public class PiiLoggerGuard extends TurboFilter {

    private final List<String> loggers = new ArrayList<>();

    /**
     * Добавляет имя логгера (или префикс пакета) под защиту. Вызывается Joran'ом на каждый
     * элемент {@code <logger>} внутри {@code <turboFilter>}.
     */
    public void addLogger(String logger) {
        if (logger != null && !logger.isBlank()) {
            loggers.add(logger.trim());
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                                        Object[] params, Throwable t) {
        if (level == null || level.isGreaterOrEqual(Level.INFO) || logger == null) {
            return FilterReply.NEUTRAL;
        }
        String name = logger.getName();
        for (String guarded : loggers) {
            if (name.equals(guarded) || name.startsWith(guarded + ".")) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
