package io.github.olegnyr.toolslog.logback;

import java.util.Arrays;

/**
 * Текстовое представление значений для JSON-полей: {@code null} — пустая строка, массивы —
 * поэлементно, прочее — {@code toString()}.
 */
final class DisplayStrings {

    private DisplayStrings() {
    }

    static String of(Object value) {
        return switch (value) {
            case null -> "";
            case String text -> text;
            case Object[] array -> Arrays.deepToString(array);
            case byte[] array -> Arrays.toString(array);
            case short[] array -> Arrays.toString(array);
            case int[] array -> Arrays.toString(array);
            case long[] array -> Arrays.toString(array);
            case char[] array -> Arrays.toString(array);
            case float[] array -> Arrays.toString(array);
            case double[] array -> Arrays.toString(array);
            case boolean[] array -> Arrays.toString(array);
            default -> value.toString();
        };
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
