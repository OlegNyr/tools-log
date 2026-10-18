package io.github.olegnyr.toolslog.servlet;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.SecretMatcher;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Внешний (самый ранний) фильтр маскировки. Делает ДВЕ вещи, обе НЕ зависят от того, включён ли
 * Logbook/HTTP-лог:
 * <ol>
 *   <li>Получает «контекст замены»: сканирует входящее JSON-тело применимыми (по URL-скоупу)
 *       {@link SecretMatcher}'ами через {@link SecretMaskingScanner} и регистрирует найденные секреты
 *       (PAN) в {@link SecretMaskingRegistry}. Тело НЕ меняет — хэндлер получает оригинал. Замена
 *       происходит в логах ({@code MaskedMessageEncoder} зовёт {@code SecretMaskingRegistry.maskAll}
 *       на message), поэтому секрет маскируется в ЛЮБОМ логе запроса (и Logbook-теле, и
 *       прикладном), даже если HTTP-Logbook выключен.</li>
 *   <li>В {@code finally} чистит реестр, чтобы секреты не протекли на пул-поток.</li>
 * </ol>
 * Стоит ВНЕ Logbook и раньше него: регистрация — до записи тела Logbook'ом, очистка — после. В Spring Boot
 * фильтр регистрирует {@code ServletLogAutoConfiguration} из {@code tools-log-spring-boot}; без Spring —
 * сервис ставит его в свою цепочку фильтров сам.
 *
 * <p>Сканируем только небольшие JSON-тела (Content-Type json, длина ≤ {@value #MAX_SCAN_BYTES}) и
 * только если на путь есть хотя бы один применимый матчер — чтобы не читать в память multipart/медиа
 * и не сканировать лишние эндпоинты.
 */
public class SecretMaskingFilter implements Filter {

    static final int MAX_SCAN_BYTES = 64 * 1024;

    /**
     * Request-attribute с «контекстом замены». Код, который формирует асинхронный ответ, кладёт сюда
     * секреты ({@code SecretMaskingRegistry.snapshotAndClear()}) в момент готовности ответа; фильтр на
     * ASYNC-диспетче (он бежит повторно, возможно на другом потоке — ThreadLocal туда не переезжает)
     * восстанавливает из него реестр до записи тела ответа Logbook'ом. Носитель — request-объект, общий
     * для REQUEST- и ASYNC-диспетчей.
     */
    public static final String SECRETS_ATTRIBUTE = SecretMaskingFilter.class.getName() + ".secrets";

    private final SecretMaskingScanner scanner;

    public SecretMaskingFilter(SecretMaskingScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ServletRequest effective = request;
        try {
            if (request.getDispatcherType() == DispatcherType.ASYNC) {
                // 2-й прогон (ASYNC re-dispatch): тело уже отсканировано на REQUEST-диспетче. Восстанавливаем
                // реестр из request-attribute (его положил код, сформировавший ответ) — Logbook
                // замаскирует тело ответа. Скан тела НЕ повторяем.
                restoreStashedSecrets(request);
            } else {
                // 1-й прогон (REQUEST): сканируем входящее JSON-тело применимыми матчерами и регистрируем.
                List<SecretMatcher> applicable = request instanceof HttpServletRequest http
                        ? scanner.applicable(http.getRequestURI())
                        : List.of();
                if (!applicable.isEmpty() && isScannableBody(request)) {
                    CachedBodyHttpServletRequest cached =
                            new CachedBodyHttpServletRequest((HttpServletRequest) request);
                    scanner.scanAndRegister(new String(cached.body(), StandardCharsets.UTF_8), applicable);
                    effective = cached;
                }
            }
            chain.doFilter(effective, response);
        } finally {
            SecretMaskingRegistry.clear();
        }
    }

    /**
     * Восстанавливает реестр из request-attribute (см. {@link #SECRETS_ATTRIBUTE}). На REQUEST-диспетче
     * атрибута ещё нет (ответ не готов) — no-op; на ASYNC-диспетче он есть → реестр populated до
     * записи ответа Logbook'ом.
     */
    @SuppressWarnings("unchecked")
    private static void restoreStashedSecrets(ServletRequest request) {
        if (request instanceof HttpServletRequest http
                && http.getAttribute(SECRETS_ATTRIBUTE) instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            SecretMaskingRegistry.setContext((Map<String, String>) secrets);
        }
    }

    private static boolean isScannableBody(ServletRequest request) {
        String contentType = request.getContentType();
        int length = request.getContentLength();
        return contentType != null && contentType.contains("json") && length >= 0 && length <= MAX_SCAN_BYTES;
    }
}
