package io.github.olegnyr.toolslog.mask;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AntPathPatternTest {

    @ParameterizedTest(name = "{0} ~ {1} = {2}")
    @CsvSource({
            "/api/users,          /api/users,            true",
            "/api/users,          /api/users/1,          false",
            "/api/**,             /api,                  true",
            "/api/**,             /api/a/b,              true",
            "/api/**,             /other,                false",
            "/**,                 /anything/at/all,      true",
            "/a/*/send/**,        /a/x/send/m,           true",
            "/a/*/send/**,        /a/x/y/send/m,         false",
            "/a/**/z,             /a/z,                  true",
            "/a/**/z,             /a/b/c/z,              true",
            "/a/**/z,             /a/b/c,                false",
            "/files/*.json,       /files/a.json,         true",
            "/files/*.json,       /files/a.txt,          false",
            "/files/*.json,       /files/sub/a.json,     false",
            "/v?/users,           /v1/users,             true",
            "/v?/users,           /v10/users,            false",
            "/a*b*c,              /aXbYc,                true",
            "/a*b*c,              /aXbY,                 false",
    })
    void matches_shouldFollowAntSemantics(String pattern, String path, boolean expected) {
        assertThat(AntPathPattern.matches(pattern, path)).isEqualTo(expected);
    }
}
