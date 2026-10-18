package io.github.olegnyr.toolslog.boot;

import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.github.olegnyr.toolslog.servlet.SecretMaskingFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.servlet.LogbookFilter;

/**
 * Фильтры входящего HTTP в servlet-приложении: маскирование секретов тела и Logbook.
 *
 * <p>Порядок: {@link SecretMaskingFilter} — самый ранний ({@code HIGHEST_PRECEDENCE}), Logbook — сразу
 * за ним. Так секреты тела зарегистрированы до того, как Logbook запишет тело, а реестр
 * очищается после записи ответа. Оба фильтра стоят до Spring Security.
 *
 * <p>Фильтр Logbook можно выключить ({@code toolslog.servlet.logbook-filter.enabled=false}) и
 * зарегистрировать {@link LogbookFilter} внутри security-цепочки, после аутентификации: тогда в
 * request-логе есть идентичность пользователя, а отклонённые на аутентификации запросы логируются
 * отдельно.
 */
@AutoConfiguration(after = {MaskingAutoConfiguration.class, LogbookAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"io.github.olegnyr.toolslog.servlet.SecretMaskingFilter",
        "org.zalando.logbook.servlet.LogbookFilter"})
public class ServletLogAutoConfiguration {

    private static final String PREFIX = ServletLogAutoConfiguration.class.getPackageName();

    /**
     * Фильтр маскирования: сканирует входящее JSON-тело и регистрирует найденные секреты. Работает
     * независимо от Logbook — секрет маскируется в любом логе запроса, даже если HTTP-лог выключен.
     *
     * @param scanner сканер секретов
     * @return регистрация фильтра
     */
    @Bean
    @ConditionalOnProperty(prefix = "toolslog.servlet.secret-masking-filter", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<SecretMaskingFilter> secretMaskingFilter(SecretMaskingScanner scanner) {
        return newFilter(new SecretMaskingFilter(scanner), PREFIX + ".secretMaskingFilter",
                Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * Глобальный фильтр Logbook для входящего HTTP.
     *
     * @param logbook общий Logbook
     * @return регистрация фильтра
     */
    @Bean
    @ConditionalOnProperty(prefix = "toolslog.servlet.logbook-filter", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<LogbookFilter> logbookFilter(Logbook logbook) {
        return newFilter(new LogbookFilter(logbook), PREFIX + ".logbookFilter", Ordered.HIGHEST_PRECEDENCE + 1);
    }

    private static <F extends Filter> FilterRegistrationBean<F> newFilter(F filter, String name, int order) {
        FilterRegistrationBean<F> registration = new FilterRegistrationBean<>(filter);
        registration.setName(name);
        // ASYNC — чтобы фильтры отработали и на повторной диспетчеризации асинхронного ответа.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setOrder(order);
        return registration;
    }
}
