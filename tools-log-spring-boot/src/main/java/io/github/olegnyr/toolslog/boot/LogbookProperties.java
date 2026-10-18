package io.github.olegnyr.toolslog.boot;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Настройки общего {@code Logbook} ({@code toolslog.logbook.*}).
 */
// final — как у прочих @ConfigurationProperties проекта (SpotBugs CT_CONSTRUCTOR_THROW).
@ConfigurationProperties(prefix = LogbookProperties.PREFIX)
@Getter
public final class LogbookProperties {

    /** Префикс группы. */
    public static final String PREFIX = "toolslog.logbook";

    /**
     * Ant-паттерны путей, обмен по которым не логируется. {@code LogbackSink} читает тело без
     * оглядки на content-type, поэтому сюда же — пути бинарной статики: иначе каждый такой ответ
     * буферизуется целиком и уезжает в лог одной записью.
     */
    private final List<String> excludePaths;

    /**
     * Заголовки (без учёта регистра), значения которых заменяются на {@code XXX}: секреты и
     * персональные данные. {@code Authorization} маскируется всегда, в списке его указывать не нужно.
     */
    private final List<String> maskedHeaders;

    /**
     * Связывание группы.
     *
     * @param excludePaths  пути, которые не логируются
     * @param maskedHeaders заголовки, значения которых маскируются
     */
    public LogbookProperties(@DefaultValue("/actuator/**") List<String> excludePaths,
                             @DefaultValue({"cookie", "set-cookie"}) List<String> maskedHeaders) {
        this.excludePaths = List.copyOf(excludePaths);
        this.maskedHeaders = List.copyOf(maskedHeaders);
    }
}
