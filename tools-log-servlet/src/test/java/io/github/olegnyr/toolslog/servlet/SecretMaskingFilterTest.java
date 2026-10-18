package io.github.olegnyr.toolslog.servlet;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.github.olegnyr.toolslog.mask.impl.DefaultSecretMaskingScanner;
import io.github.olegnyr.toolslog.mask.impl.PanSecretMatcher;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretMaskingFilterTest {

    private final SecretMaskingFilter filter =
            new SecretMaskingFilter(new DefaultSecretMaskingScanner(List.of(
                    new PanSecretMatcher(List.of("/api/messages/**"), List.of()))));

    @AfterEach
    void clear() {
        SecretMaskingRegistry.clear();
    }

    private static final String SEND_PATH = "/api/messages/42";

    @Test
    void doFilter_shouldRegisterPanFromBody_soLogsMaskIt_andLeaveBodyReadable_andClearAfter() throws Exception {
        byte[] body = "{\"body\":\"оплата 4111111111111111\"}".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(SEND_PATH);
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLength()).thenReturn(body.length);
        when(request.getInputStream()).thenReturn(servletInputStream(body));
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] maskedInChain = new String[1];
        byte[][] bodySeenByHandler = new byte[1][];
        filter.doFilter(request, response, (req, resp) -> {
            // во время цепочки PAN в реестре → логгер замаскировал бы его
            maskedInChain[0] = SecretMaskingRegistry.maskAll("app log: 4111111111111111");
            // тело осталось читаемым для хэндлера (кэш-обёртка)
            bodySeenByHandler[0] = ((HttpServletRequest) req).getInputStream().readAllBytes();
        });

        assertThat(maskedInChain[0]).isEqualTo("app log: ************1111");
        assertThat(bodySeenByHandler[0]).isEqualTo(body);
        // после запроса реестр очищен — нет протечки на пул-поток
        assertThat(SecretMaskingRegistry.maskAll("4111111111111111")).isEqualTo("4111111111111111");
    }

    @Test
    void doFilter_shouldNotScan_whenNotJson() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(SEND_PATH);
        when(request.getContentType()).thenReturn("multipart/form-data");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] maskedInChain = new String[1];
        filter.doFilter(request, response, (req, resp) ->
                maskedInChain[0] = SecretMaskingRegistry.maskAll("4111111111111111"));

        // тело не сканировалось → PAN не зарегистрирован
        assertThat(maskedInChain[0]).isEqualTo("4111111111111111");
    }

    @Test
    void doFilter_shouldSkipMatcher_whenPathOutsideItsUrlScope() throws Exception {
        // Матчер ограничен путями сообщений; /api/users вне scope → PAN не трогаем.
        byte[] body = "{\"username\":\"4111111111111111\"}".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/users");
        HttpServletResponse response = mock(HttpServletResponse.class);

        String[] maskedInChain = new String[1];
        filter.doFilter(request, response, (req, resp) ->
                maskedInChain[0] = SecretMaskingRegistry.maskAll("4111111111111111"));

        assertThat(maskedInChain[0]).isEqualTo("4111111111111111");
    }

    private static ServletInputStream servletInputStream(byte[] data) {
        ByteArrayInputStream source = new ByteArrayInputStream(data);
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
                // no-op
            }

            @Override
            public int read() {
                return source.read();
            }
        };
    }
}
