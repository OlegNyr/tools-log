package io.github.olegnyr.toolslog.mask;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SecretMaskingRegistryTest {

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    @Test
    void maskAll_shouldReplaceRegisteredSecretsLiterally() {
        SecretMaskingRegistry.register("syt_secretvalue", "XXX");
        SecretMaskingRegistry.register("srt_refreshvalue", "YYY");

        String masked = SecretMaskingRegistry.maskAll("token=syt_secretvalue refresh=srt_refreshvalue user=@a");

        assertThat(masked).isEqualTo("token=XXX refresh=YYY user=@a")
                .doesNotContain("syt_secretvalue").doesNotContain("srt_refreshvalue");
    }

    @Test
    void maskAll_shouldBeNoOp_whenRegistryEmpty() {
        assertThat(SecretMaskingRegistry.maskAll("nothing to mask")).isEqualTo("nothing to mask");
    }

    @Test
    void maskAll_shouldReturnNull_whenInputNull() {
        assertThat(SecretMaskingRegistry.maskAll(null)).isNull();
    }

    @Test
    void register_shouldIgnoreBlankSecret() {
        SecretMaskingRegistry.register("", "XXX");
        SecretMaskingRegistry.register(null, "XXX");

        assertThat(SecretMaskingRegistry.maskAll("text")).isEqualTo("text");
    }

    @Test
    void clear_shouldRemoveSecrets_soTheyDoNotLeakAcrossRequests() {
        SecretMaskingRegistry.register("syt_secret", "XXX");

        SecretMaskingRegistry.clear();

        assertThat(SecretMaskingRegistry.maskAll("syt_secret")).isEqualTo("syt_secret");
    }

    @Test
    void register_shouldNotBeVisibleOnAnotherThread_whenRegisteredHere() throws Exception {
        // Arrange
        SecretMaskingRegistry.register("syt_secret", "XXX");

        // Act: чужой поток обслуживает чужой запрос — реестр там должен быть пустым
        String[] seenByOtherThread = new String[1];
        Thread other = new Thread(() -> seenByOtherThread[0] = SecretMaskingRegistry.maskAll("syt_secret"));
        other.start();
        other.join();

        // Assert
        assertThat(seenByOtherThread[0]).isEqualTo("syt_secret");
    }

    @Test
    void snapshotAndClear_shouldReturnSecretsAndEmptyRegistry_whenResponseLeavesThisThread() {
        // Arrange
        SecretMaskingRegistry.register("syt_secret", "XXX");

        // Act
        var snapshot = SecretMaskingRegistry.snapshotAndClear();

        // Assert
        assertThat(snapshot).containsExactly(entry("syt_secret", "XXX"));
        assertThat(SecretMaskingRegistry.maskAll("syt_secret")).isEqualTo("syt_secret");
    }

    @Test
    void setContext_shouldRemoveRegistry_whenRestoringNullOnPooledThread() {
        // Arrange: так context-propagation восстанавливает «пусто» после задачи на пул-потоке
        SecretMaskingRegistry.register("syt_secret", "XXX");

        // Act
        SecretMaskingRegistry.setContext(null);

        // Assert
        assertThat(SecretMaskingRegistry.getContext()).isNull();
    }
}
