package io.github.olegnyr.toolslog.mask.impl;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.SecretMatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;

/**
 * Реализация {@link SecretMaskingScanner} на {@link SecretMatcher}-бинах. Прогоняет текст по матчерам
 * и регистрирует найденные секреты в {@link SecretMaskingRegistry}.
 */
@Slf4j
public class DefaultSecretMaskingScanner implements SecretMaskingScanner {

    private final List<SecretMatcher> matchers;

    public DefaultSecretMaskingScanner(List<SecretMatcher> matchers) {
        this.matchers = List.copyOf(matchers);
    }

    @Override
    public List<SecretMatcher> applicable(String requestPath) {
        return matchers.stream().filter(matcher -> matcher.appliesTo(requestPath)).toList();
    }

    @Override
    public void scanAndRegister(String text) {
        scanAndRegister(text, matchers);
    }

    @Override
    public void scanAndRegister(String text, List<SecretMatcher> withMatchers) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (SecretMatcher matcher : withMatchers) {
            Matcher found = matcher.pattern().matcher(text);
            while (found.find()) {
                String mask = matcher.mask(found.group());
                if (mask != null) {
                    SecretMaskingRegistry.register(found.group(), mask);
                    log.debug("secret masked by matcher '{}'", matcher.alias());
                }
            }
        }
    }
}
