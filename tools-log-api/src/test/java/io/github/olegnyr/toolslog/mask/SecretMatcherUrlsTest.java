package io.github.olegnyr.toolslog.mask;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMatcherUrlsTest {

    @Test
    void applies_shouldBeTrue_whenIncludeEmpty() {
        assertThat(SecretMatcherUrls.applies("/any/path", List.of(), List.of())).isTrue();
    }

    @Test
    void applies_shouldMatchIncludePattern() {
        assertThat(SecretMatcherUrls.applies("/a/rooms/x/send/m", List.of("/a/rooms/*/send/**"), List.of()))
                .isTrue();
        assertThat(SecretMatcherUrls.applies("/a/register", List.of("/a/rooms/*/send/**"), List.of()))
                .isFalse();
    }

    @Test
    void applies_shouldLetExcludeWinOverInclude() {
        assertThat(SecretMatcherUrls.applies("/a/admin/x", List.of("/a/**"), List.of("/a/admin/**")))
                .isFalse();
    }

    @Test
    void applies_shouldBeTrue_whenPathNull_andNoInclude() {
        assertThat(SecretMatcherUrls.applies(null, List.of(), List.of())).isTrue();
        assertThat(SecretMatcherUrls.applies(null, List.of("/x/**"), List.of())).isFalse();
    }
}
