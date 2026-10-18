package io.github.olegnyr.toolslog.logback;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.SerializableString;

import java.io.IOException;
import java.util.Map;

@AllArgsConstructor
class SimpleJsonGenerator {

    private final JsonGenerator json;

    @SneakyThrows
    public SimpleJsonGenerator writeStartObject() {
        json.writeStartObject();
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeObjectFieldStart(String fieldName) {
        json.writeObjectPropertyStart(fieldName);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeEndObject() {
        json.writeEndObject();
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeNumberField(String fieldName, long num) {
        json.writeNumberProperty(fieldName, num);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeStringField(String fieldName, String value) {
        json.writeStringProperty(fieldName, value);
        return this;
    }

    public SimpleJsonGenerator writeNullable(String name, Object value) throws IOException {
        if (value != null) {
            String string = DisplayStrings.of(value);
            if (DisplayStrings.hasText(string)) {
                json.writeStringProperty(name, string);
            }
        }
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeNullField(String fieldName) {
        json.writeNullProperty(fieldName);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeArrayFieldStart(String fieldName) {
        json.writeArrayPropertyStart(fieldName);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeString(String value) {
        json.writeString(value);
        return this;

    }

    @SneakyThrows
    public SimpleJsonGenerator writeEndArray() {
        json.writeEndArray();
        return this;
    }

    // --- Overloads принимающие SerializableString: для стабильных (константных) имён полей
    //     заквоченные UTF-8-байты имени кэшируются внутри SerializedString, поэтому writeName
    //     не прогоняет имя через экранирование/кодирование на каждое событие (см. FormatterElastic).

    @SneakyThrows
    public SimpleJsonGenerator writeObjectFieldStart(SerializableString fieldName) {
        json.writeName(fieldName);
        json.writeStartObject();
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeArrayFieldStart(SerializableString fieldName) {
        json.writeName(fieldName);
        json.writeStartArray();
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeNumberField(SerializableString fieldName, long num) {
        json.writeName(fieldName);
        json.writeNumber(num);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeStringField(SerializableString fieldName, String value) {
        json.writeName(fieldName);
        json.writeString(value);
        return this;
    }

    @SneakyThrows
    public SimpleJsonGenerator writeStringField(SerializableString fieldName, SerializableString value) {
        json.writeName(fieldName);
        json.writeString(value);
        return this;
    }

    /**
     * Пишет заранее отрендеренный JSON-фрагмент как значение поля через {@code writeRawValue}:
     * unquoted UTF-8-байты {@code rawValue} копируются как есть (значение не экранируется).
     * Используется для константных поддеревьев, отрендеренных один раз в конструкторе.
     */
    @SneakyThrows
    public SimpleJsonGenerator writeRawValueField(SerializableString fieldName, SerializableString rawValue) {
        json.writeName(fieldName);
        json.writeRawValue(rawValue);
        return this;
    }

    public SimpleJsonGenerator writeNullable(SerializableString name, Object value) throws IOException {
        if (value != null) {
            String string = DisplayStrings.of(value);
            if (DisplayStrings.hasText(string)) {
                json.writeName(name);
                json.writeString(string);
            }
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public void writeMap(Map<String, Object> structure) {
        for (Map.Entry<String, Object> entry : structure.entrySet()) {
            switch (entry.getValue()) {

                case String s -> writeStringField(entry.getKey(), s);
                case Map<?, ?> m -> {
                    writeObjectFieldStart(entry.getKey());
                    writeMap((Map<String, Object>) m);
                    writeEndObject();
                }
                case null -> writeNullField(entry.getKey());
                default -> writeStringField(entry.getKey(), DisplayStrings.of(entry.getValue()));

            }
        }
    }
}
