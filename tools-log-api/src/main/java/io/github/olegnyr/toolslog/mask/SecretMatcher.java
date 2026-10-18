package io.github.olegnyr.toolslog.mask;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Распознаватель одного класса секретов в тексте для маскировки — точка расширения для сервисов:
 * номера карт, документы, телефоны, ключи API. Сервису достаточно зависимости {@code tools-log-api}.
 *
 * <p>Сканер ({@link SecretMaskingScanner}) прогоняет по тексту все матчеры и регистрирует найденное
 * в {@link SecretMaskingRegistry}; замену делает логгер. В Spring Boot матчер — просто бин: сканер
 * из {@code tools-log-spring-boot} соберёт все бины {@code SecretMatcher}. Без Spring матчеры
 * передаются конструктору сканера. Правок фильтров и перехватчиков не нужно.
 *
 * <p>Матчер ограничивает себя по URL: {@link #includeUrlPatterns()} / {@link #excludeUrlPatterns()}
 * (Ant-паттерны: {@code ?}, {@code *}, {@code **}) — входящее HTTP-тело прогоняется матчером только
 * на подходящих путях. Это и точность (номер карты ждём в теле сообщения, а не при регистрации), и
 * производительность (не сканируем лишнее). Для текста не из HTTP — тела Kafka-записи, контента из
 * базы — пути нет, и применяются все матчеры.
 */
public interface SecretMatcher {

    /** Короткий алиас матчера (для логов/конфигурации), напр. {@code "pan"}. */
    String alias();

    /** Regex, которым ищем кандидатов в тексте. */
    Pattern pattern();

    /**
     * Возвращает маску для совпадения, либо {@code null}, если кандидат не является секретом и
     * маскировать его не нужно (напр. длинное число не прошло Luhn-проверку для PAN).
     */
    String mask(String match);

    /** Ant-паттерны URL, на которых матчер применяется. Пусто = на всех (с учётом exclude). */
    default List<String> includeUrlPatterns() {
        return List.of();
    }

    /** Ant-паттерны URL, на которых матчер НЕ применяется (приоритет над include). */
    default List<String> excludeUrlPatterns() {
        return List.of();
    }

    /** Применять ли матчер к данному пути запроса (по include/exclude). */
    default boolean appliesTo(String requestPath) {
        return SecretMatcherUrls.applies(requestPath, includeUrlPatterns(), excludeUrlPatterns());
    }
}
