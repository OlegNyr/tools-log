package io.github.olegnyr.toolslog.logbook;

import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpHeaders;
import org.zalando.logbook.HttpMessage;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.RequestURI;
import org.zalando.logbook.Sink;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.zalando.logbook.RequestURI.reconstruct;

@SuppressWarnings("PMD.MoreThanOneLogger")
public class LogbackSink implements Sink {

    public static final Set<RequestURI.Component> COMPONENTS = EnumSet.allOf(RequestURI.Component.class);
    private final org.slf4j.Logger logIncoming;
    private final org.slf4j.Logger logOutgoing;

    public LogbackSink(org.slf4j.Logger log) {
        logIncoming = LoggerFactory.getLogger(log.getName() + ".incoming");
        logOutgoing = LoggerFactory.getLogger(log.getName() + ".outgoing");
    }

    @Override
    public boolean isActive() {
        return logIncoming.isInfoEnabled() || logOutgoing.isInfoEnabled();
    }

    @Override
    public void write(Precorrelation precorrelation, HttpRequest request) throws IOException {
        final String body = request.getBodyAsString();
        LoggingEventBuilder eventBuilder = makeEventBuilder(request.getOrigin());

        String url = reconstruct(request, COMPONENTS);
        eventBuilder = eventBuilder
                .addKeyValue("correlation", precorrelation.getId())
                .addKeyValue("operation", "request")
                .addKeyValue("origin", direction(request))
                .addKeyValue("url", url);

        if (hasText(request.getRemote())) {
            eventBuilder = eventBuilder.addKeyValue("remote", request.getRemote());
        }

        if (hasText(request.getProtocolVersion())) {
            eventBuilder = eventBuilder.addKeyValue("protocolVersion", request.getProtocolVersion());
        }

        if (hasText(request.getMethod())) {
            eventBuilder = eventBuilder.addKeyValue("method", request.getMethod());
        }

        for (var entry : request.getAttributes().entrySet()) {
            eventBuilder = eventBuilder.addKeyValue("attribute." + entry.getKey(), entry.getValue());
        }

        fillHeader(eventBuilder, request.getHeaders()).log(isEmpty(body) ? url : body);
    }

    @Override
    public void write(Correlation correlation, HttpRequest request, HttpResponse response) throws IOException {
        final String body = response.getBodyAsString();
        LoggingEventBuilder eventBuilder = makeEventBuilder(response.getOrigin());

        eventBuilder = eventBuilder
                .addKeyValue("correlation", correlation.getId())
                .addKeyValue("operation", "response")
                .addKeyValue("origin", direction(response))
                .addKeyValue("duration", correlation.getDuration().toMillis());

        if (hasText(response.getReasonPhrase())) {
            eventBuilder = eventBuilder.addKeyValue("reason", response.getReasonPhrase());
        }

        if (hasText(response.getProtocolVersion())) {
            eventBuilder = eventBuilder
                    .addKeyValue("protocolVersion", response.getProtocolVersion());
        }

        for (var entry : response.getAttributes().entrySet()) {
            eventBuilder = eventBuilder.addKeyValue("attribute." + entry.getKey(), entry.getValue());
        }

        fillHeader(eventBuilder, response.getHeaders()).log(isEmpty(body) ? response.getReasonPhrase() : body);
    }

    private static LoggingEventBuilder fillHeader(LoggingEventBuilder eventBuilder, HttpHeaders headers) {
        LoggingEventBuilder result = eventBuilder;
        StringBuilder output = new StringBuilder();
        for (var entry : headers.entrySet()) {
            final List<String> headerValues = entry.getValue();
            output.setLength(0);
            if (!headerValues.isEmpty()) {
                for (final String valueOne : headerValues) {
                    output.append(valueOne).append(", ");
                }
                output.setLength(output.length() - 2); // discard last comma
            }
            result = result.addKeyValue("header." + entry.getKey(), output.toString());
        }
        return result;
    }

    private LoggingEventBuilder makeEventBuilder(Origin response) {
        return response == Origin.REMOTE
                ? logIncoming.atInfo() : logOutgoing.atInfo();
    }

    private String direction(final HttpMessage request) {
        return request.getOrigin() == Origin.REMOTE ? "incoming" : "outgoing";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
