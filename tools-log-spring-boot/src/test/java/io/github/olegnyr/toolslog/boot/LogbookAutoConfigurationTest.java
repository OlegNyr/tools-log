package io.github.olegnyr.toolslog.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zalando.logbook.Logbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LogbookAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogbookAutoConfiguration.class));

    @Test
    @DisplayName("Без настроек исключён актуатор, маскируются cookie")
    void properties_shouldHaveDefaults_whenNotConfigured() {
        runner.run(context -> {
            LogbookProperties properties = context.getBean(LogbookProperties.class);

            assertThat(context).hasSingleBean(Logbook.class);
            assertThat(properties.getExcludePaths()).containsExactly("/actuator/**");
            assertThat(properties.getMaskedHeaders()).containsExactly("cookie", "set-cookie");
        });
    }

    @Test
    @DisplayName("Пути и заголовки берутся из настроек")
    void properties_shouldBind_whenConfigured() {
        runner.withPropertyValues(
                        "toolslog.logbook.exclude-paths=/actuator/**,/static/**",
                        "toolslog.logbook.masked-headers=X-User-Id")
                .run(context -> {
                    LogbookProperties properties = context.getBean(LogbookProperties.class);

                    assertThat(properties.getExcludePaths()).containsExactly("/actuator/**", "/static/**");
                    assertThat(properties.getMaskedHeaders()).containsExactly("X-User-Id");
                });
    }

    @Test
    @DisplayName("Свой Logbook сервиса заменяет библиотечный")
    void logbook_shouldBackOff_whenServiceDeclaresOwn() {
        Logbook own = mock(Logbook.class);

        runner.withBean(Logbook.class, () -> own)
                .run(context -> assertThat(context.getBean(Logbook.class)).isSameAs(own));
    }
}
