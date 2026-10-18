package io.github.olegnyr.toolslog.logback;

import lombok.Builder;

import java.util.stream.Stream;

/**
 * Поля сервиса в каждом событии JSON-лога: {@code system}, {@code service}, {@code reporter} — на
 * верхнем уровне, остальные — в объекте {@code servicesc}. Пустые поля не пишутся; полностью пустой
 * {@code ServiceInfo} — блока нет совсем.
 *
 * @param system      система, к которой относится сервис
 * @param service     сервис внутри системы
 * @param reporter    источник событий
 * @param name        имя приложения
 * @param version     версия приложения
 * @param environment окружение (стенд)
 * @param nodeName    имя узла
 * @param commit      коммит сборки
 */
@Builder(toBuilder = true)
public record ServiceInfo(String system, String service, String reporter, String name, String version,
                          String environment, String nodeName, String commit) {

    /** Пустые поля сервиса. */
    public static final ServiceInfo NONE = ServiceInfo.builder().build();

    /**
     * Есть ли хоть одно непустое поле.
     *
     * @return {@code true}, если все поля пусты
     */
    public boolean isEmpty() {
        return Stream.of(system, service, reporter, name, version, environment, nodeName, commit)
                .noneMatch(DisplayStrings::hasText);
    }

    /**
     * Дополняет пустые поля значениями из другого {@code ServiceInfo}.
     *
     * @param fallback значения на случай пустых полей
     * @return новый {@code ServiceInfo}
     */
    public ServiceInfo orElse(ServiceInfo fallback) {
        return new ServiceInfo(
                pick(system, fallback.system), pick(service, fallback.service), pick(reporter, fallback.reporter),
                pick(name, fallback.name), pick(version, fallback.version),
                pick(environment, fallback.environment), pick(nodeName, fallback.nodeName),
                pick(commit, fallback.commit));
    }

    private static String pick(String value, String fallback) {
        return DisplayStrings.hasText(value) ? value : fallback;
    }
}
