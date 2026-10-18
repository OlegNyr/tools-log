package io.github.olegnyr.toolslog.kafka;

import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;

import java.io.IOException;

public class KafkaConsumerHttpResponse implements HttpResponse {
    private final int status;
    private final String message;

    public KafkaConsumerHttpResponse(Exception exception) {
        this.status = exception == null ? 200 : 500;
        this.message = exception == null ? null
                : org.apache.commons.lang3.exception.ExceptionUtils.getRootCauseMessage(exception);
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public HttpResponse withBody() throws IOException {
        return this;
    }

    @Override
    public HttpResponse withoutBody() {
        return this;
    }

    @Override
    public String getReasonPhrase() {
        return message;
    }

    @Override
    public String getProtocolVersion() {
        return null;
    }

    @Override
    public Origin getOrigin() {
        return Origin.LOCAL;
    }

    @Override
    public HttpHeaders getHeaders() {
        return HttpHeaders.empty();
    }

    @Override
    public byte[] getBody() throws IOException {
        return new byte[0];
    }
}
