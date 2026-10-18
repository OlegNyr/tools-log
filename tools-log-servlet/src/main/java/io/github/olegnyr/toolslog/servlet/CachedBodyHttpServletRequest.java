package io.github.olegnyr.toolslog.servlet;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Обёртка запроса с телом, прочитанным в память: тело становится перечитываемым (каждый
 * {@link #getInputStream()} отдаёт свежий поток над байтами), поэтому {@link SecretMaskingFilter}
 * может просканировать его на секреты, а ниже по цепочке Logbook и хэндлер прочитают тело снова.
 * Используется только для небольших JSON-тел (см. {@link SecretMaskingFilter}).
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    // PMD.MethodReturnsInternalArray: копия массива на каждый вызов удвоила бы
    // память на запрос; caller (SecretMaskingFilter) только читает байты.
    @SuppressWarnings("PMD.MethodReturnsInternalArray")
    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // синхронное чтение — async ReadListener не поддерживаем
            }

            @Override
            public int read() {
                return source.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }
}
