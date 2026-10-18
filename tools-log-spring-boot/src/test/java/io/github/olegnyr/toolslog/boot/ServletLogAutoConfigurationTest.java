package io.github.olegnyr.toolslog.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

class ServletLogAutoConfigurationTest {

    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(
            MaskingAutoConfiguration.class, LogbookAutoConfiguration.class, ServletLogAutoConfiguration.class);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(CONFIGURATIONS);

    @Test
    @DisplayName("Фильтр маскирования стоит первым, Logbook — сразу за ним")
    void filters_shouldBeOrdered_whenServletApplication() {
        runner.run(context -> {
            FilterRegistrationBean<?> masking = context.getBean("secretMaskingFilter", FilterRegistrationBean.class);
            FilterRegistrationBean<?> logbook = context.getBean("logbookFilter", FilterRegistrationBean.class);

            assertThat(masking.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(logbook.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        });
    }

    @Test
    @DisplayName("Фильтр Logbook выключается настройкой, фильтр маскирования остаётся")
    void logbookFilter_shouldBeAbsent_whenDisabled() {
        runner.withPropertyValues("toolslog.servlet.logbook-filter.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("logbookFilter")
                        .hasBean("secretMaskingFilter"));
    }

    @Test
    @DisplayName("Вне servlet-приложения фильтров нет")
    void filters_shouldBeAbsent_whenNotWebApplication() {
        new ApplicationContextRunner().withConfiguration(CONFIGURATIONS)
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
