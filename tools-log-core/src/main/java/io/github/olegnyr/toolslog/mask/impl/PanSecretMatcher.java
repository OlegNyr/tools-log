package io.github.olegnyr.toolslog.mask.impl;

import io.github.olegnyr.toolslog.mask.Masks;
import io.github.olegnyr.toolslog.mask.SecretMask;
import io.github.olegnyr.toolslog.mask.SecretMatcher;

import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link SecretMatcher} для номеров карт (PAN): 13–19 цифр (с разделителями {@code ' '}/{@code '-'}),
 * прошедших Luhn-проверку (она отсекает ложные срабатывания — timestamp/id из длинных цифр).
 * Маска оставляет последние 4 цифры.
 *
 * <p>По умолчанию применяется на всех путях. Сужать — Ant-паттернами путей, где клиент вводит
 * свободный текст и номер карты может оказаться в теле: так не сканируются лишние эндпоинты.
 * В Spring Boot пути задаются настройками {@code toolslog.masking.pan.*}.
 */
public final class PanSecretMatcher implements SecretMatcher {

    private static final Pattern PAN = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;
    private static final SecretMask LAST_DIGITS = Masks.keepLast(4);

    private final List<String> includeUrlPatterns;
    private final List<String> excludeUrlPatterns;

    /** Матчер для всех путей. */
    public PanSecretMatcher() {
        this(List.of(), List.of());
    }

    /**
     * Матчер, ограниченный путями.
     *
     * @param includeUrlPatterns Ant-паттерны путей, где матчер применяется; пусто — все пути
     * @param excludeUrlPatterns Ant-паттерны путей, где матчер не применяется (приоритет над include)
     */
    public PanSecretMatcher(List<String> includeUrlPatterns, List<String> excludeUrlPatterns) {
        this.includeUrlPatterns = List.copyOf(includeUrlPatterns);
        this.excludeUrlPatterns = List.copyOf(excludeUrlPatterns);
    }

    @Override
    public String alias() {
        return "pan";
    }

    @Override
    public List<String> includeUrlPatterns() {
        return includeUrlPatterns;
    }

    @Override
    public List<String> excludeUrlPatterns() {
        return excludeUrlPatterns;
    }

    @Override
    public Pattern pattern() {
        return PAN;
    }

    @Override
    public String mask(String match) {
        String digits = match.replace(" ", "").replace("-", "");
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS || !luhnValid(digits)) {
            return null;
        }
        return LAST_DIGITS.apply(digits);
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }
}
