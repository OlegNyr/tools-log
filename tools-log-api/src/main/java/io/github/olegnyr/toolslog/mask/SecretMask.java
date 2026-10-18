package io.github.olegnyr.toolslog.mask;

/**
 * Маска секрета: по значению секрета — текст, который появится в логе вместо него. Готовые маски —
 * {@link Masks}.
 */
@FunctionalInterface
public interface SecretMask {

    /**
     * Маскирует значение.
     *
     * @param secret значение секрета; не {@code null} и не пустое
     * @return текст для лога вместо секрета
     */
    String apply(String secret);
}
