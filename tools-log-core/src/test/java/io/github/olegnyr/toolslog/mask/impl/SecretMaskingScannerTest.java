package io.github.olegnyr.toolslog.mask.impl;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.SecretMaskingScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskingScannerTest {

    private final SecretMaskingScanner scanner = new DefaultSecretMaskingScanner(
            List.of(new PanSecretMatcher(List.of("/api/messages/**"), List.of())));

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    @Test
    void scanAndRegister_allMatchers_shouldRegisterPanIgnoringUrlScope() {
        // для контента не из HTTP (база, брокер) URL-скоуп не применяется — сканируем всеми матчерами
        scanner.scanAndRegister("{\"body\":\"карта 4111111111111111\"}");

        assertThat(SecretMaskingRegistry.maskAll("4111111111111111")).isEqualTo("************1111");
    }

    @Test
    void scanAndRegister_shouldBeNoOp_whenNullOrEmpty() {
        scanner.scanAndRegister(null);
        scanner.scanAndRegister("");

        assertThat(SecretMaskingRegistry.maskAll("4111111111111111")).isEqualTo("4111111111111111");
    }

    @Test
    void applicable_shouldFilterMatchersByPath() {
        assertThat(scanner.applicable("/api/messages/42")).hasSize(1);
        assertThat(scanner.applicable("/api/users")).isEmpty();
    }
}
