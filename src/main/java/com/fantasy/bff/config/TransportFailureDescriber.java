package com.fantasy.bff.config;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Names what actually happened when a downstream call got no answer. The JDK client reports its
 * own read timeout as a cancelled future, so Spring's {@code JdkClientHttpRequest} raises an
 * {@code HttpTimeoutException} carrying the cancellation's message - "Request cancelled" or "get",
 * depending on which side of the future saw it first - and a refused connection can arrive with no
 * message at all. In Sentry those read as a browser that went away, or as nothing
 * (JAVA-SPRING-BOOT-32, 36, 3A, 3E). The failure keeps its type, and so its handling and the db
 * reads' retry; only the sentence changes, to say which service, which budget, and that it ran out.
 *
 * <p>This covers a failure before the response head arrives, which is where every one of those
 * landed. A deadline that elapses while the body is being read closes the stream under the reader,
 * after this interceptor has returned.
 */
final class TransportFailureDescriber implements ClientHttpRequestInterceptor {

    private static final int MAX_CAUSE_DEPTH = 10;

    private final String service;
    private final long timeoutMs;

    TransportFailureDescriber(String service, Duration timeout) {
        this.service = service;
        this.timeoutMs = timeout.toMillis();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        try {
            return execution.execute(request, body);
        } catch (IOException e) {
            throw describe(e);
        }
    }

    private IOException describe(IOException e) {
        List<Throwable> chain = chain(e);
        IOException described;
        if (chain.stream().anyMatch(HttpConnectTimeoutException.class::isInstance)) {
            described = new HttpConnectTimeoutException(
                    service + " accepted no connection within the " + timeoutMs + " ms connect timeout");
        } else if (chain.stream().anyMatch(HttpTimeoutException.class::isInstance)) {
            described = new HttpTimeoutException(
                    service + " sent no response within the " + timeoutMs + " ms read timeout");
        } else if (chain.stream().allMatch(cause -> isBlank(cause.getMessage()))) {
            described = new IOException("The connection to " + service + " failed with no message ("
                    + String.join(" <- ", chain.stream().map(cause -> cause.getClass().getSimpleName()).toList())
                    + ")");
        } else {
            return e;
        }
        described.initCause(e);
        return described;
    }

    private static List<Throwable> chain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = e; cause != null && chain.size() < MAX_CAUSE_DEPTH; cause = cause.getCause()) {
            chain.add(cause);
            if (cause.getCause() == cause) {
                break;
            }
        }
        return chain;
    }

    private static boolean isBlank(String message) {
        return message == null || message.isBlank();
    }
}
