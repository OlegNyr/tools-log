package io.github.olegnyr.toolslog.mask;

import java.util.Arrays;

/**
 * Сопоставление пути запроса с Ant-паттерном: {@code ?} — один символ, {@code *} — любые символы
 * внутри сегмента, {@code **} — любое число сегментов, в том числе ноль. Пустые сегменты (двойной и
 * хвостовой {@code /}) не учитываются.
 *
 * <p>Своя реализация вместо {@code AntPathMatcher} из Spring: модуль ядра работает без Spring.
 * Регулярных выражений нет — перебор по сегментам и символам, линейный для паттернов без {@code **}.
 */
final class AntPathPattern {

    private static final String ANY_SEGMENTS = "**";

    private AntPathPattern() {
    }

    static boolean matches(String pattern, String path) {
        return matchSegments(segments(pattern), 0, segments(path), 0);
    }

    private static String[] segments(String value) {
        return Arrays.stream(value.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);
    }

    private static boolean matchSegments(String[] pattern, int patternIndex, String[] path, int pathIndex) {
        if (patternIndex == pattern.length) {
            return pathIndex == path.length;
        }
        if (ANY_SEGMENTS.equals(pattern[patternIndex])) {
            for (int next = pathIndex; next <= path.length; next++) {
                if (matchSegments(pattern, patternIndex + 1, path, next)) {
                    return true;
                }
            }
            return false;
        }
        return pathIndex < path.length
                && matchSegment(pattern[patternIndex], path[pathIndex])
                && matchSegments(pattern, patternIndex + 1, path, pathIndex + 1);
    }

    /** Глоб внутри сегмента: жадный проход с возвратом к последней {@code *}. */
    private static boolean matchSegment(String pattern, String text) {
        int patternPos = 0;
        int textPos = 0;
        int starPos = -1;
        int starTextPos = 0;
        while (textPos < text.length()) {
            if (patternPos < pattern.length()
                    && (pattern.charAt(patternPos) == '?' || pattern.charAt(patternPos) == text.charAt(textPos))) {
                patternPos++;
                textPos++;
            } else if (patternPos < pattern.length() && pattern.charAt(patternPos) == '*') {
                starPos = patternPos++;
                starTextPos = textPos;
            } else if (starPos >= 0) {
                patternPos = starPos + 1;
                textPos = ++starTextPos;
            } else {
                return false;
            }
        }
        while (patternPos < pattern.length() && pattern.charAt(patternPos) == '*') {
            patternPos++;
        }
        return patternPos == pattern.length();
    }
}
