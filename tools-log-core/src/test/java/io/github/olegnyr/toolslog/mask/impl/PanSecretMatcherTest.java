package io.github.olegnyr.toolslog.mask.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PanSecretMatcherTest {

    private final PanSecretMatcher matcher = new PanSecretMatcher();

    @Test
    void mask_shouldKeepLast4_whenLuhnValid() {
        assertThat(matcher.mask("4111111111111111")).isEqualTo("************1111");
    }

    @Test
    void mask_shouldStripSeparators_andKeepLast4() {
        assertThat(matcher.mask("4111-1111-1111-1111")).isEqualTo("************1111");
    }

    @Test
    void mask_shouldReturnNull_whenNotLuhnValid() {
        // 13 единиц — не проходит Luhn → не PAN.
        assertThat(matcher.mask("1111111111111")).isNull();
    }

    @Test
    void alias_shouldBePan() {
        assertThat(matcher.alias()).isEqualTo("pan");
    }

    @Test
    void appliesTo_shouldMatchAnyPath_whenNotRestricted() {
        assertThat(matcher.appliesTo("/api/users")).isTrue();
    }

    @Test
    void appliesTo_shouldMatchIncludedPath_butNotOthers() {
        PanSecretMatcher restricted = new PanSecretMatcher(List.of("/api/messages/**"), List.of());

        assertThat(restricted.appliesTo("/api/messages/42")).isTrue();
        assertThat(restricted.appliesTo("/api/users")).isFalse();
    }

    @Test
    void appliesTo_shouldSkipExcludedPath_evenWhenIncluded() {
        PanSecretMatcher restricted = new PanSecretMatcher(List.of("/api/**"), List.of("/api/health"));

        assertThat(restricted.appliesTo("/api/health")).isFalse();
    }
}
