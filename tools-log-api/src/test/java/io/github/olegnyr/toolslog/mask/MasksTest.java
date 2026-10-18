package io.github.olegnyr.toolslog.mask;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasksTest {

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    @Test
    @DisplayName("hidden() скрывает и значение, и его длину")
    void hidden_shouldReturnFixedStars_whateverTheLength() {
        assertThat(Masks.hidden().apply("a")).isEqualTo("***");
        assertThat(Masks.hidden().apply("very-long-secret-value")).isEqualTo("***");
    }

    @Test
    @DisplayName("all() заменяет каждый символ звёздочкой")
    void all_shouldReplaceEveryCharacter() {
        assertThat(Masks.all().apply("Иванов")).isEqualTo("******");
    }

    @ParameterizedTest(name = "keepLast(4): {0} -> {1}")
    @CsvSource({
            "4111111111111111, ************1111",
            "12345,            *2345",
            "1234,             ****",
            "12,               **",
    })
    @DisplayName("keepLast оставляет хвост, а короткое значение скрывает целиком")
    void keepLast_shouldKeepTail_butHideWhollyWhenTooShort(String secret, String expected) {
        assertThat(Masks.keepLast(4).apply(secret)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "keepFirst(2): {0} -> {1}")
    @CsvSource({
            "AB123456, AB******",
            "AB,       **",
    })
    @DisplayName("keepFirst оставляет начало, а короткое значение скрывает целиком")
    void keepFirst_shouldKeepHead_butHideWhollyWhenTooShort(String secret, String expected) {
        assertThat(Masks.keepFirst(2).apply(secret)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "keepFirstAndLast(6, 4): {0} -> {1}")
    @CsvSource({
            "4111111111111111, 411111******1111",
            "4111111111,       **********",
    })
    @DisplayName("keepFirstAndLast оставляет края, а короткое значение скрывает целиком")
    void keepFirstAndLast_shouldKeepEdges_butHideWhollyWhenTooShort(String secret, String expected) {
        assertThat(Masks.keepFirstAndLast(6, 4).apply(secret)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "email: {0} -> {1}")
    @CsvSource({
            "ivan.petrov@example.com, i***@example.com",
            "a@example.com,           *@example.com",
            "not-an-email,            ************",
    })
    @DisplayName("email() оставляет первую букву и домен")
    void email_shouldKeepFirstLetterAndDomain(String secret, String expected) {
        assertThat(Masks.email().apply(secret)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Символ вне BMP не разрезается пополам")
    void keepLast_shouldCountCodePoints_notChars() {
        String withEmoji = "ab😀";

        assertThat(Masks.keepLast(1).apply(withEmoji)).isEqualTo("**😀");
    }

    @Test
    @DisplayName("Отрицательное число видимых символов — ошибка при создании маски")
    void keepLast_shouldReject_whenCountNegative() {
        assertThatThrownBy(() -> Masks.keepLast(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Регистрация с маской заменяет значение в логе результатом маски")
    void register_shouldApplyMask_whenMaskGiven() {
        SecretMaskingRegistry.register("+79991234567", Masks.keepLast(2));

        assertThat(SecretMaskingRegistry.maskAll("phone=+79991234567")).isEqualTo("phone=**********67");
    }

    @Test
    @DisplayName("Пустой секрет с маской не регистрируется и маску не вызывает")
    void register_shouldIgnoreBlankSecret_whenMaskGiven() {
        SecretMaskingRegistry.register("", secret -> {
            throw new AssertionError("маска не должна вызываться");
        });

        assertThat(SecretMaskingRegistry.maskAll("text")).isEqualTo("text");
    }
}
