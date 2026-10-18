package io.github.olegnyr.toolslog.boot;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.SecretMatcher;
import io.github.olegnyr.toolslog.mask.impl.PanSecretMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaskingAutoConfiguration.class));

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    @Test
    @DisplayName("По умолчанию матчер карт применяется на всех путях")
    void context_shouldRegisterPanMatcherForAllPaths_whenNoProperties() {
        runner.run(context -> {
            SecretMaskingScanner scanner = context.getBean(SecretMaskingScanner.class);

            assertThat(scanner.applicable("/any/path")).extracting(SecretMatcher::alias).containsExactly("pan");
        });
    }

    @Test
    @DisplayName("Пути матчера карт берутся из настроек")
    void context_shouldRestrictPanMatcher_whenPathsConfigured() {
        runner.withPropertyValues(
                        "toolslog.masking.pan.include-paths=/api/messages/**",
                        "toolslog.masking.pan.exclude-paths=/api/messages/health")
                .run(context -> {
                    SecretMaskingScanner scanner = context.getBean(SecretMaskingScanner.class);

                    assertThat(scanner.applicable("/api/messages/42")).hasSize(1);
                    assertThat(scanner.applicable("/api/messages/health")).isEmpty();
                    assertThat(scanner.applicable("/api/users")).isEmpty();
                });
    }

    @Test
    @DisplayName("Выключенный матчер карт не регистрируется")
    void context_shouldNotRegisterPanMatcher_whenDisabled() {
        runner.withPropertyValues("toolslog.masking.pan.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PanSecretMatcher.class)
                        .hasSingleBean(SecretMaskingScanner.class));
    }

    @Test
    @DisplayName("Матчер сервиса попадает в сканер вместе с матчером карт")
    void context_shouldIncludeServiceMatcher_whenDeclaredAsBean() {
        runner.withUserConfiguration(TokenMatcherConfiguration.class).run(context -> {
            context.getBean(SecretMaskingScanner.class).scanAndRegister("token=tk-123");

            assertThat(SecretMaskingRegistry.maskAll("tk-123")).isEqualTo("***");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TokenMatcherConfiguration {

        @Bean
        SecretMatcher tokenMatcher() {
            return new SecretMatcher() {
                @Override
                public String alias() {
                    return "token";
                }

                @Override
                public Pattern pattern() {
                    return Pattern.compile("tk-\\d+");
                }

                @Override
                public String mask(String match) {
                    return "***";
                }
            };
        }
    }
}
