package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiLoggerGuard: DEBUG у ПДн-логгеров глушится независимо от уровня")
class PiiLoggerGuardTest {

    private static final String GUARDED = "com.example.orders.persistence.customer";

    private LoggerContext context;
    private PiiLoggerGuard guard;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        guard = new PiiLoggerGuard();
        guard.addLogger(GUARDED);
    }

    @Test
    @DisplayName("decide должен отбросить DEBUG защищённого логгера, даже если уровень выставлен в DEBUG")
    void decide_shouldDeny_whenGuardedLoggerLogsDebug() {
        // Arrange — ровно то, что делает POST /actuator/loggers
        ch.qos.logback.classic.Logger logger = context.getLogger(GUARDED + ".CustomerMapper");
        logger.setLevel(Level.DEBUG);

        // Act
        FilterReply reply = guard.decide(null, logger, Level.DEBUG, "==> Parameters: {}", null, null);

        // Assert — иначе SQL-маппер напечатал бы значения параметров: телефон, ФИО, дату рождения
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("decide не должен трогать INFO и выше — диагностика остаётся рабочей")
    void decide_shouldStayNeutral_whenLevelIsInfoOrAbove() {
        // Arrange
        ch.qos.logback.classic.Logger logger = context.getLogger(GUARDED + ".CustomerMapper");

        // Act & Assert
        assertThat(guard.decide(null, logger, Level.INFO, "msg", null, null))
            .isEqualTo(FilterReply.NEUTRAL);
        assertThat(guard.decide(null, logger, Level.WARN, "msg", null, null))
            .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("decide не должен трогать чужие логгеры: глушится только заявленный пакет")
    void decide_shouldStayNeutral_whenLoggerIsNotGuarded() {
        // Arrange — похожее имя, но другой пакет
        ch.qos.logback.classic.Logger other = context.getLogger("com.example.orders.persistence.order");

        // Act
        FilterReply reply = guard.decide(null, other, Level.DEBUG, "msg", null, null);

        // Assert
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
