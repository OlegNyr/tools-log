package io.github.olegnyr.toolslog.mask;

import java.util.List;

/**
 * Сопоставление пути запроса со списками include/exclude Ant-паттернов {@link SecretMatcher}'а.
 * Правила: exclude имеет приоритет; пустой include = «все пути».
 */
final class SecretMatcherUrls {

    private SecretMatcherUrls() {
    }

    static boolean applies(String requestPath, List<String> includes, List<String> excludes) {
        if (requestPath == null) {
            // путь неизвестен — применяем только если матчер не ограничен include'ами
            return includes.isEmpty();
        }
        for (String exclude : excludes) {
            if (AntPathPattern.matches(exclude, requestPath)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (String include : includes) {
            if (AntPathPattern.matches(include, requestPath)) {
                return true;
            }
        }
        return false;
    }
}
