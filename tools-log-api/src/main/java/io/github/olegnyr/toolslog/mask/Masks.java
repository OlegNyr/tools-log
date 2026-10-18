package io.github.olegnyr.toolslog.mask;

/**
 * Готовые маски для {@link SecretMaskingRegistry#register(String, SecretMask)} и {@link SecretMatcher}.
 *
 * <p>Символы считаются по code point: символ вне BMP (эмодзи) не разрезается пополам. Маска, которая
 * оставляет часть символов видимыми, скрывает значение целиком, если оно не длиннее видимой части, —
 * иначе короткий секрет попал бы в лог как есть.
 */
public final class Masks {

    private static final String STAR = "*";
    private static final String HIDDEN = "***";

    private Masks() {
    }

    /**
     * Фиксированные {@code ***}: скрывают и значение, и его длину. Для паролей и токенов.
     *
     * @return маска
     */
    public static SecretMask hidden() {
        return secret -> HIDDEN;
    }

    /**
     * Звёздочка на каждый символ: длина значения видна.
     *
     * @return маска
     */
    public static SecretMask all() {
        return secret -> STAR.repeat(length(secret));
    }

    /**
     * Видны последние {@code visible} символов: {@code 4111111111111111} → {@code ************1111}.
     *
     * @param visible сколько последних символов оставить
     * @return маска
     */
    public static SecretMask keepLast(int visible) {
        return keepFirstAndLast(0, visible);
    }

    /**
     * Видны первые {@code visible} символов: {@code AB123456} → {@code AB******}.
     *
     * @param visible сколько первых символов оставить
     * @return маска
     */
    public static SecretMask keepFirst(int visible) {
        return keepFirstAndLast(visible, 0);
    }

    /**
     * Видны первые {@code first} и последние {@code last} символов: номер карты с BIN —
     * {@code keepFirstAndLast(6, 4)} → {@code 411111******1111}.
     *
     * @param first сколько первых символов оставить
     * @param last  сколько последних символов оставить
     * @return маска
     */
    public static SecretMask keepFirstAndLast(int first, int last) {
        if (first < 0 || last < 0) {
            throw new IllegalArgumentException("Число видимых символов не может быть отрицательным: "
                    + first + ", " + last);
        }
        return secret -> {
            int length = length(secret);
            if (length <= first + last) {
                return STAR.repeat(length);
            }
            int headEnd = secret.offsetByCodePoints(0, first);
            int tailStart = secret.offsetByCodePoints(secret.length(), -last);
            return secret.substring(0, headEnd) + STAR.repeat(length - first - last) + secret.substring(tailStart);
        };
    }

    /**
     * Адрес почты: видны первая буква и домен — {@code ivan.petrov@example.com} → {@code i***@example.com}.
     * Значение без {@code @} скрывается целиком, как {@link #all()}.
     *
     * @return маска
     */
    public static SecretMask email() {
        return secret -> {
            int at = secret.lastIndexOf('@');
            if (at <= 0) {
                return all().apply(secret);
            }
            String local = secret.substring(0, at);
            String maskedLocal = length(local) == 1
                    ? STAR
                    : local.substring(0, local.offsetByCodePoints(0, 1)) + HIDDEN;
            return maskedLocal + secret.substring(at);
        };
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
