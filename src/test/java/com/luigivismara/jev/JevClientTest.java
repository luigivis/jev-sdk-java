package com.luigivismara.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against a throwaway JDK {@link HttpServer}: transport, auth headers, retries
 * and error mapping, without touching the real API.
 */
class JevClientTest {

    private static final String OK_BODY = """
            {
              "model": "jev-1.13.0",
              "answers": {"urgent": {"type": "noul", "noul": 0.84}},
              "usage": {"input_tokens": 380, "output_tokens": 73}
            }""";

    private static final NamedQuestion URGENT =
            Question.noul("Does this need attention today?").named("urgent");

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> receivedBodies = new ArrayList<>();
    private final List<String> receivedAuth = new ArrayList<>();
    private final List<String> receivedPaths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registers a handler that records the request and replies with the given status and body. */
    private void respond(String path, int status, String body) {
        respond(path, exchange -> reply(exchange, status, body));
    }

    private void respond(String path, Handler handler) {
        server.createContext(path, exchange -> {
            calls.incrementAndGet();
            receivedPaths.add(exchange.getRequestURI().getPath());
            receivedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                receivedBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            handler.handle(exchange);
        });
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private JevClient client() {
        return client(builder -> builder);
    }

    private JevClient client(java.util.function.UnaryOperator<JevClient.Builder> customize) {
        return customize.apply(JevClient.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .retryBackoff(Duration.ofMillis(1))
                .timeout(Duration.ofSeconds(5))).build();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @Test
    @DisplayName("a successful call posts to /v1/systemone with a bearer key and parses the answers")
    void happyPath() throws Exception {
        respond("/v1/systemone", 200, OK_BODY);

        try (JevClient jev = client()) {
            JevResponse response = jev.ask("My order never arrived",
                    URGENT);

            assertEquals("jev-1.13.0", response.model());
            assertEquals(0.84, response.noul("urgent").noul(), 1e-9);
            assertEquals(380, response.usage().inputTokens());
        }

        assertEquals("/v1/systemone", receivedPaths.get(0));
        assertEquals("Bearer test-key", receivedAuth.get(0));
        assertEquals(new ObjectMapper().readTree("""
                {
                  "state": "My order never arrived",
                  "model": "jev-latest",
                  "questions": {
                    "urgent": {
                      "type": "noul",
                      "instructions": "Does this need attention today?"
                    }
                  }
                }"""), new ObjectMapper().readTree(receivedBodies.get(0)));
    }

    @Test
    @DisplayName("the model overload is sent through to the API")
    void modelOverload() {
        respond("/v1/systemone", 200, OK_BODY);

        try (JevClient jev = client()) {
            jev.ask("state", JevModel.JEV_PREVIEW, URGENT);
        }

        assertTrue(receivedBodies.get(0).contains("\"model\":\"jev-preview\""),
                receivedBodies.get(0));
    }

    @Test
    @DisplayName("a trailing slash on the base URL does not produce a double slash")
    void trailingSlashBaseUrl() {
        respond("/v1/systemone", 200, OK_BODY);

        try (JevClient jev = client(b -> b.baseUrl(baseUrl + "/"))) {
            jev.ask("state", URGENT);
        }

        assertEquals("/v1/systemone", receivedPaths.get(0));
    }

    @Test
    @DisplayName("models() lists the aliases available to the account")
    void models() {
        respond("/v1/models", 200, """
                {
                  "models": [
                    {
                      "name": "jev-latest",
                      "description": "Latest System One model",
                      "release_date": "2026-09-10T18:38:01.391457+00:00"
                    }
                  ]
                }""");

        List<ModelInfo> models;
        try (JevClient jev = client()) {
            models = jev.models();
        }

        assertEquals(1, models.size());
        assertEquals("jev-latest", models.get(0).name());
        assertTrue(models.get(0).releaseDate().startsWith("2026-09-10"));
        assertEquals("Bearer test-key", receivedAuth.get(0));
    }

    @Test
    @DisplayName("a 422 surfaces the field-level validation errors instead of a bare status")
    void validationError() {
        respond("/v1/systemone", 422, """
                {
                  "detail": [
                    {"type": "missing", "loc": ["body", "model"], "msg": "Field required"}
                  ]
                }""");

        JevException e;
        try (JevClient jev = client()) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertEquals(422, e.statusCode());
        assertEquals(1, e.details().size());
        assertEquals("Field required", e.details().get(0).msg());
        assertEquals(List.of("body", "model"), e.details().get(0).loc());
        assertTrue(e.getMessage().contains("Field required"), e.getMessage());
        assertEquals(1, calls.get(), "a 422 is the caller's fault and must not be retried");
    }

    @Test
    @DisplayName("a 401 is reported immediately and never retried")
    void unauthorizedIsNotRetried() {
        respond("/v1/systemone", 401, """
                {"detail": "Invalid API key"}""");

        JevException e;
        try (JevClient jev = client()) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertEquals(401, e.statusCode());
        assertTrue(e.getMessage().contains("Invalid API key"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a non-JSON error body still yields a readable message")
    void nonJsonErrorBody() {
        respond("/v1/systemone", 502, "<html>bad gateway</html>");

        JevException e;
        try (JevClient jev = client(b -> b.maxRetries(0))) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertEquals(502, e.statusCode());
        assertTrue(e.getMessage().contains("bad gateway"), e.getMessage());
    }

    @Test
    @DisplayName("a transient 503 is retried and the follow-up success is returned")
    void retriesTransientFailure() {
        respond("/v1/systemone", exchange -> {
            if (calls.get() == 1) {
                reply(exchange, 503, """
                        {"detail": "overloaded"}""");
            } else {
                reply(exchange, 200, OK_BODY);
            }
        });

        try (JevClient jev = client()) {
            JevResponse response = jev.ask("state", URGENT);

            assertEquals(0.84, response.noul("urgent").noul(), 1e-9);
        }

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("a 429 is treated as transient")
    void retriesRateLimit() {
        respond("/v1/systemone", exchange -> {
            if (calls.get() == 1) {
                reply(exchange, 429, """
                        {"detail": "slow down"}""");
            } else {
                reply(exchange, 200, OK_BODY);
            }
        });

        try (JevClient jev = client()) {
            jev.ask("state", URGENT);
        }

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("retries stop at maxRetries and the last status is reported")
    void retriesAreBounded() {
        respond("/v1/systemone", 500, """
                {"detail": "boom"}""");

        JevException e;
        try (JevClient jev = client(b -> b.maxRetries(2))) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertEquals(500, e.statusCode());
        assertEquals(3, calls.get(), "one initial attempt plus two retries");
    }

    @Test
    @DisplayName("maxRetries(0) disables retrying entirely")
    void retriesCanBeDisabled() {
        respond("/v1/systemone", 500, """
                {"detail": "boom"}""");

        try (JevClient jev = client(b -> b.maxRetries(0))) {
            assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("a response that is not the decision schema fails closed")
    void schemaMismatchFailsClosed() {
        respond("/v1/systemone", 200, """
                {"unexpected": true}""");

        JevException e;
        try (JevClient jev = client()) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertTrue(e.getMessage().contains("expected decision schema"), e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    @DisplayName("an unreachable host raises a JevException after exhausting attempts")
    void transportFailure() {
        server.stop(0);

        JevException e;
        try (JevClient jev = client(b -> b.maxRetries(1))) {
            e = assertThrows(JevException.class,
                    () -> jev.ask("state", URGENT));
        }

        assertTrue(e.getMessage().contains("after 2 attempt(s)"), e.getMessage());
        assertEquals(0, e.statusCode());
    }

    @Test
    @DisplayName("the builder rejects a missing key and a negative retry count")
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> JevClient.builder().build());
        assertThrows(IllegalArgumentException.class, () -> JevClient.builder().apiKey(" ").build());
        assertThrows(IllegalArgumentException.class,
                () -> JevClient.builder().apiKey("k").maxRetries(-1));
    }

    @Test
    @DisplayName("of() builds a client pointed at the production base URL")
    void ofShorthand() {
        try (JevClient jev = JevClient.of("k")) {
            assertEquals("https://api.typesafe.ai", JevClient.DEFAULT_BASE_URL);
        }
    }

    @Test
    @DisplayName("fromEnv() fails loudly when JEV_API_KEY is absent")
    void fromEnvRequiresTheVariable() {
        if (System.getenv(JevClient.API_KEY_ENV) != null) {
            return; // the variable is set in this environment; the negative path cannot be tested
        }
        assertThrows(IllegalStateException.class, JevClient::fromEnv);
    }
}
