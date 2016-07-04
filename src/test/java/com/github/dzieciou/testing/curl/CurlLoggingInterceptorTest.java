package com.github.dzieciou.testing.curl;

import com.jayway.restassured.config.HttpClientConfig;
import com.jayway.restassured.config.RestAssuredConfig;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.mockserver.client.server.MockServerClient;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import uk.org.lidalia.slf4jext.Level;
import uk.org.lidalia.slf4jtest.LoggingEvent;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

import static com.jayway.restassured.RestAssured.config;
import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.config.HttpClientConfig.httpClientConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static uk.org.lidalia.slf4jtest.LoggingEvent.debug;
import static uk.org.lidalia.slf4jtest.LoggingEvent.info;

public class CurlLoggingInterceptorTest {

    public static final int MOCK_PORT = 9999;
    public static final String MOCK_HOST = "localhost";
    public static final String MOCK_BASE_URI = "http://" + MOCK_HOST;
    private MockServerClient mockServer;
    private TestLogger log = TestLoggerFactory.getTestLogger("curl");

    @BeforeClass
    public void setupMock() {
        mockServer = startClientAndServer(MOCK_PORT);
        mockServer.when(request()).respond(response());
    }

    @Test
    public void shouldLogDebugMessageWithCurlCommand() {

        // given
        RestAssuredConfig restAssuredConfig = getRestAssuredConfig(CurlLoggingInterceptor.defaultBuilder().build());

        // when
        //@formatter:off
        given()
                .redirects().follow(false)
                .baseUri( MOCK_BASE_URI)
                .port(MOCK_PORT)
                .config( restAssuredConfig)
        .when()
                .get("/")
        .then()
                .statusCode(200);
        //@formatter:on

        // then
        assertThat(log.getAllLoggingEvents().size(), is(1));
        LoggingEvent firstEvent = log.getLoggingEvents().get(0);
        assertThat(firstEvent.getLevel(), is(Level.DEBUG));
        assertThat(firstEvent.getMessage(), startsWith("curl"));
    }

    @Test
    public void shouldLogStacktraceWhenEnabled() {

        // given
        RestAssuredConfig restAssuredConfig = getRestAssuredConfig(CurlLoggingInterceptor.defaultBuilder().logStacktrace().build());

        // when
        //@formatter:off
        given()
                .redirects().follow(false)
                .baseUri( MOCK_BASE_URI)
                .port(MOCK_PORT)
                .config( restAssuredConfig)
        .when()
                .get("/")
        .then()
                .statusCode(200);
        //@formatter:on

        // then
        assertThat(log.getAllLoggingEvents().size(), is(1));
        LoggingEvent firstEvent = log.getLoggingEvents().get(0);
        assertThat(firstEvent.getLevel(), is(Level.DEBUG));
        assertThat(firstEvent.getMessage(), both(startsWith("curl")).and(containsString("generated")).and(containsString(("java.lang.Thread.getStackTrace"))));
    }

    @AfterMethod
    public void clearLoggers() {
        log.clearAll();
        TestLoggerFactory.clear();
    }

    @AfterClass
    public void stopMockServer() {
        mockServer.stop();
    }

    private static RestAssuredConfig getRestAssuredConfig(CurlLoggingInterceptor curlLoggingInterceptor) {
        return config()
                .httpClient(httpClientConfig()
                        .reuseHttpClientInstance().httpClientFactory(new MyHttpClientFactory(curlLoggingInterceptor)));
    }

    private static class MyHttpClientFactory implements HttpClientConfig.HttpClientFactory {

        private CurlLoggingInterceptor curlLoggingInterceptor;

        public MyHttpClientFactory(CurlLoggingInterceptor curlLoggingInterceptor) {
            this.curlLoggingInterceptor = curlLoggingInterceptor;
        }

        @Override
        public HttpClient createHttpClient() {
            AbstractHttpClient client = new DefaultHttpClient();
            client.addRequestInterceptor(curlLoggingInterceptor);
            return client;
        }
    }
}
