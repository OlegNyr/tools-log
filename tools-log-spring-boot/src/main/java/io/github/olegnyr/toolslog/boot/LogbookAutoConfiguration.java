package io.github.olegnyr.toolslog.boot;

import io.github.olegnyr.toolslog.logbook.LogbackSink;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.core.Conditions;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.zalando.logbook.core.Conditions.exclude;
import static org.zalando.logbook.core.HeaderFilters.authorization;
import static org.zalando.logbook.core.HeaderFilters.replaceHeaders;

/**
 * Общий {@link Logbook} для всех транспортов — входящего HTTP, Kafka и HTTP-клиентов сервиса: одни правила
 * маскирования заголовков и один {@link LogbackSink}.
 *
 * <p>Заголовки с секретами маскирует сам Logbook до того, как sink их прочитает: заголовки
 * логирует только он. Тела маскируются на этапе записи лога — по реестру секретов
 * ({@code SecretMaskingRegistry}), а не правкой тела.
 *
 * <p>Свой бин {@link Logbook} в сервисе отключает этот.
 */
@AutoConfiguration
@EnableConfigurationProperties(LogbookProperties.class)
public class LogbookAutoConfiguration {

    /** Логгер обменов; {@link LogbackSink} пишет в {@code .incoming} и {@code .outgoing}. */
    public static final String REQUEST_LOGGER = "toolslog.request";

    /** Замена значения маскируемого заголовка. */
    private static final String MASK = "XXX";

    /**
     * Logbook с исключёнными путями и маскированием заголовков из настроек.
     *
     * @param properties настройки Logbook
     * @return Logbook
     */
    @Bean
    @ConditionalOnMissingBean
    public Logbook logbook(LogbookProperties properties) {
        Set<String> masked = properties.getMaskedHeaders().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        List<Predicate<HttpRequest>> excluded = properties.getExcludePaths().stream()
                .map(Conditions::<HttpRequest>requestTo)
                .toList();
        return Logbook.builder()
                .condition(exclude(excluded))
                .headerFilter(HeaderFilter.merge(
                        authorization(),
                        replaceHeaders(name -> name != null && masked.contains(name.toLowerCase(Locale.ROOT)), MASK)))
                .sink(new LogbackSink(LoggerFactory.getLogger(REQUEST_LOGGER)))
                .build();
    }
}
