package io.github.olegnyr.toolslog.boot;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Настройки маскирования секретов ({@code toolslog.masking.*}).
 */
// final — как у прочих @ConfigurationProperties проекта (SpotBugs CT_CONSTRUCTOR_THROW).
@ConfigurationProperties(prefix = MaskingProperties.PREFIX)
@Getter
public final class MaskingProperties {

    /** Префикс группы. */
    public static final String PREFIX = "toolslog.masking";

    /** Маскирование номеров карт. */
    private final PanProperties pan;

    /**
     * Связывание группы.
     *
     * @param pan маскирование номеров карт
     */
    public MaskingProperties(@DefaultValue PanProperties pan) {
        this.pan = pan;
    }

    /**
     * Маскирование номеров карт ({@code toolslog.masking.pan.*}).
     */
    @Getter
    public static final class PanProperties {

        /** Регистрировать ли матчер номеров карт. */
        private final boolean enabled;

        /** Ant-паттерны путей, на которых тело сканируется на номера карт; пусто — все пути. */
        private final List<String> includePaths;

        /** Ant-паттерны путей, на которых тело не сканируется (приоритет над include-paths). */
        private final List<String> excludePaths;

        /**
         * Связывание группы.
         *
         * @param enabled      регистрировать ли матчер
         * @param includePaths пути, где матчер применяется
         * @param excludePaths пути, где матчер не применяется
         */
        public PanProperties(@DefaultValue("true") boolean enabled, List<String> includePaths,
                             List<String> excludePaths) {
            this.enabled = enabled;
            this.includePaths = includePaths == null ? List.of() : List.copyOf(includePaths);
            this.excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
        }
    }
}
