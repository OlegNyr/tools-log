package io.github.olegnyr.toolslog.boot;

import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.SecretMatcher;
import io.github.olegnyr.toolslog.mask.impl.DefaultSecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.impl.PanSecretMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Сканер секретов над всеми {@link SecretMatcher}-бинами контекста и матчер номеров карт.
 *
 * <p>Новый класс секрета — новый {@link SecretMatcher}-бин сервиса: сканер соберёт его вместе с
 * матчером карт, правок фильтров и перехватчиков не нужно.
 */
@AutoConfiguration
@EnableConfigurationProperties(MaskingProperties.class)
public class MaskingAutoConfiguration {

    /**
     * Матчер номеров карт с путями из настроек.
     *
     * @param properties настройки маскирования
     * @return матчер
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "toolslog.masking.pan", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public PanSecretMatcher panSecretMatcher(MaskingProperties properties) {
        MaskingProperties.PanProperties pan = properties.getPan();
        return new PanSecretMatcher(pan.getIncludePaths(), pan.getExcludePaths());
    }

    /**
     * Сканер над всеми матчерами контекста.
     *
     * @param matchers матчеры секретов; ни одного — сканер ничего не находит
     * @return сканер
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretMaskingScanner secretMaskingScanner(ObjectProvider<SecretMatcher> matchers) {
        return new DefaultSecretMaskingScanner(matchers.orderedStream().toList());
    }
}
