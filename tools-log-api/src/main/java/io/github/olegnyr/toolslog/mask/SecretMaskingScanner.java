package io.github.olegnyr.toolslog.mask;

import java.util.List;

/**
 * Прогоняет текст через {@link SecretMatcher}'ы и регистрирует найденные секреты в реестр маскировки
 * ({@link SecretMaskingRegistry}; замену делают логгеры). Реализация — {@code DefaultSecretMaskingScanner}
 * из {@code tools-log-core}. Интерфейс — чтобы транспорты зависели только от API, а тесты брали
 * {@link #NOOP} вместо сборки матчеров.
 *
 * <p>Переиспользуется: {@code SecretMaskingFilter} (входящее HTTP-тело по URL-скоупу матчеров),
 * Kafka-consume (тело записи), код сервиса — для контента не из HTTP (например, из базы).
 */
public interface SecretMaskingScanner {

    /** No-op реализация для тестов без матчеров. */
    SecretMaskingScanner NOOP = new SecretMaskingScanner() {
        @Override
        public List<SecretMatcher> applicable(String requestPath) {
            return List.of();
        }

        @Override
        public void scanAndRegister(String text) {
            // Матчеров нет — регистрировать нечего.
        }

        @Override
        public void scanAndRegister(String text, List<SecretMatcher> matchers) {
            // Матчеров нет — регистрировать нечего.
        }
    };

    /** Матчеры, применимые к пути запроса (по include/exclude URL). */
    List<SecretMatcher> applicable(String requestPath);

    /** Сканирует текст ВСЕМИ матчерами (без URL-скоупа) — для контента из БД / Kafka. */
    void scanAndRegister(String text);

    /** Сканирует текст заданным набором матчеров и регистрирует найденные секреты. */
    void scanAndRegister(String text, List<SecretMatcher> matchers);
}
