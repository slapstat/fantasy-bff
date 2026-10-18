package com.fantasy.bff.config;

import com.fantasy.bff.support.WireMockConfigs;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A downstream call that got no answer says so, naming the service and the budget it ran out of,
 * instead of the JDK's "Request cancelled" or "get" (JAVA-SPRING-BOOT-32, 36, 3A) or a bare null
 * (3E, 3F, 3G).
 *
 * <p>The read timeout is provoked for real through the bean {@link RestClientConfig} builds, so
 * the test also proves every ordinary downstream client carries the describer. As in
 * {@code HttpDatabaseServiceClientReadRetryTest}, the delay is twice the timeout so a cold JVM
 * cannot make a prompt answer look late, or a late one look prompt.
 */
class TransportFailureDescriberTest {

    private static final int TIMEOUT_MS = 1000;
    private static final int LONGER_THAN_THE_TIMEOUT_MS = 2000;

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfigs.http11());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void aReadTimeoutNamesTheServiceAndItsBudget() {
        server.stubFor(get(urlPathEqualTo("/slow")).willReturn(okJson("{}").withFixedDelay(LONGER_THAN_THE_TIMEOUT_MS)));
        RestClient client = new RestClientConfig().databaseServiceClient(server.baseUrl(), TIMEOUT_MS, keys());

        Throwable thrown = catchThrowable(() -> client.get().uri("/slow").retrieve().body(String.class));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("db-service sent no response within the 1000 ms read timeout")
                .hasMessageNotContaining("Request cancelled")
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        assertThat(thrown.getCause()).isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void aConnectTimeoutStaysAConnectTimeout() {
        TransportFailureDescriber describer = new TransportFailureDescriber("db-service", Duration.ofMillis(3000));

        assertThatThrownBy(() -> describer.intercept(request(), new byte[0],
                failingWith(new IOException("HTTP connect timed out", new HttpConnectTimeoutException("HTTP connect timed out")))))
                .isInstanceOf(HttpConnectTimeoutException.class)
                .hasMessage("db-service accepted no connection within the 3000 ms connect timeout");
    }

    @Test
    void aFailureWithNoMessageNamesTheServiceAndTheChain() {
        TransportFailureDescriber describer = new TransportFailureDescriber("yahoo-service", Duration.ofMillis(10000));
        ConnectException refused = new ConnectException();
        refused.initCause(new ClosedChannelException());

        assertThatThrownBy(() -> describer.intercept(request(), new byte[0], failingWith(refused)))
                .isInstanceOf(IOException.class)
                .hasMessage("The connection to yahoo-service failed with no message (ConnectException <- ClosedChannelException)")
                .cause().isSameAs(refused);
    }

    @Test
    void aFailureThatAlreadySaysWhatHappenedIsLeftAlone() {
        TransportFailureDescriber describer = new TransportFailureDescriber("yahoo-service", Duration.ofMillis(10000));
        ConnectException noRoute = new ConnectException("No route to host (connect failed)");

        assertThatThrownBy(() -> describer.intercept(request(), new byte[0], failingWith(noRoute)))
                .isSameAs(noRoute);
    }

    private static ClientHttpRequestExecution failingWith(IOException failure) {
        return (request, body) -> {
            throw failure;
        };
    }

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.GET, URI.create("http://downstream/anything"));
    }

    private static InternalApiKeyProperties keys() {
        InternalApiKeyProperties.Downstream key = new InternalApiKeyProperties.Downstream("test-key");
        return new InternalApiKeyProperties(key, key, key, key);
    }
}
