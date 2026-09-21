package com.luigivismara.jev;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client for the TypeSafe AI Jev (System One) decision API.
 *
 * <p>Instances are immutable and safe to share across threads; create one per application and
 * reuse it.
 *
 * <pre>{@code
 * try (JevClient jev = JevClient.fromEnv()) {
 *     JevResponse res = jev.ask("My order never arrived",
 *             Question.noul("Is this urgent?").named("urgent"));
 *     if (res.noul("urgent").noul() > 0.8) escalate();
 * }
 * }</pre>
 *
 * <p>The API key is never taken from source: read it from {@code JEV_API_KEY} with
 * {@link #fromEnv()} or pass it in from your own secret store.
 */
public final class JevClient implements AutoCloseable {

    /** Default API base URL. */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

    /** Environment variable read by {@link #fromEnv()}. */
    public static final String API_KEY_ENV = "JEV_API_KEY";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final HttpClient http;

    private JevClient(Builder builder) {
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        this.apiKey = builder.apiKey;
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.timeout = builder.timeout;
        this.maxRetries = builder.maxRetries;
        this.retryBackoff = builder.retryBackoff;
        this.http = builder.httpClient != null ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(builder.timeout).build();
    }

    /**
     * A client using the key in the {@value #API_KEY_ENV} environment variable.
     *
     * <pre>{@code
     * // export JEV_API_KEY="apikey_..."
     * try (JevClient jev = JevClient.fromEnv()) {
     *     jev.ask("hello", Question.noul("Is this a greeting?").named("greeting"));
     * }
     * }</pre>
     *
     * @return a client with default timeout and retry settings
     * @throws IllegalStateException if that variable is unset or blank
     */
    public static JevClient fromEnv() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(API_KEY_ENV + " is not set");
        }
        return builder().apiKey(key).build();
    }

    /**
     * A client with the given key and all other settings defaulted.
     *
     * <p>Use it when the key comes from somewhere other than the environment:
     *
     * <pre>{@code
     * JevClient jev = JevClient.of(secretsManager.get("jev-api-key"));
     * }</pre>
     *
     * @param apiKey the TypeSafe API key; must not be blank
     * @return a client with default timeout and retry settings
     * @throws IllegalArgumentException if {@code apiKey} is null or blank
     */
    public static JevClient of(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /**
     * A builder, for when the defaults do not fit.
     *
     * <pre>{@code
     * JevClient jev = JevClient.builder()
     *         .apiKey(secrets.get("jev-api-key"))
     *         .timeout(Duration.ofSeconds(10))
     *         .maxRetries(3)
     *         .build();
     * }</pre>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Evaluates {@code request} and returns the answers.
     *
     * <p>Take this overload when you need the builder — a non-default model, or questions
     * assembled across several methods:
     *
     * <pre>{@code
     * JevRequest request = JevRequest.builder()
     *         .state(ticket)
     *         .model(JevModel.JEV_PREVIEW)
     *         .ask("routing", Question.choice("Which team?", TEAMS))
     *         .ask("urgent",  Question.noul("Does this need attention today?"))
     *         .build();
     *
     * JevResponse response = jev.ask(request);
     * }</pre>
     *
     * <p>{@code JevRequest} is a record of records, so it compares structurally — it works
     * directly as a cache key when the same decision recurs:
     *
     * <pre>{@code
     * Cache<JevRequest, JevResponse> cache = Caffeine.newBuilder()
     *         .maximumSize(10_000)
     *         .expireAfterWrite(Duration.ofHours(1))
     *         .build();
     *
     * JevResponse response = cache.get(request, jev::ask);
     * }</pre>
     *
     * @param request the content and questions to evaluate
     * @return the answers, keyed by the names in {@code request}
     * @throws JevException on a transport failure, a non-2xx response, or an unreadable body
     */
    public JevResponse ask(JevRequest request) {
        String body = send("/v1/systemone", request);
        try {
            return MAPPER.readValue(body, JevResponse.class);
        } catch (IOException e) {
            // Fail closed on schema mismatch rather than coercing into a default branch.
            throw new JevException("response did not match the expected decision schema", e);
        }
    }

    /**
     * Evaluates {@code questions} about {@code state} using {@link JevModel#JEV_LATEST}.
     *
     * <p>The short form, and the one to reach for by default. Ask everything you need about one
     * piece of content in a single call — the content is billed once, however many questions ride
     * along:
     *
     * <pre>{@code
     * JevResponse response = jev.ask(ticket,
     *         Question.choice("Which team should handle this?", TEAMS).named("routing"),
     *         Question.noul("Does this need attention today?").named("urgent"),
     *         Question.score("How severe is this?",
     *                 "Can wait", "This week", "Today").named("severity"));
     * }</pre>
     *
     * @param state     the content the questions refer to: a string, a record, or any value that
     *                  serializes to JSON
     * @param questions the questions, with distinct names
     * @return the answers, filed under those names
     * @throws IllegalArgumentException if {@code state} is null or the names collide
     * @throws JevException             on a transport failure or a non-2xx response
     */
    public JevResponse ask(Object state, NamedQuestion... questions) {
        return ask(state, JevModel.JEV_LATEST, questions);
    }

    /**
     * Evaluates {@code questions} about {@code state} using the given model.
     *
     * <pre>{@code
     * JevResponse response = jev.ask(ticket, JevModel.JEV_PREVIEW,
     *         Question.noul("Does this need attention today?").named("urgent"));
     * }</pre>
     *
     * @param state     the content the questions refer to
     * @param model     the model or alias to use
     * @param questions the questions, with distinct names
     * @return the answers, filed under those names
     * @throws IllegalArgumentException if {@code state} or {@code model} is null
     * @throws JevException             on a transport failure or a non-2xx response
     */
    public JevResponse ask(Object state, JevModel model, NamedQuestion... questions) {
        return ask(new JevRequest(state, model, questions));
    }

    /**
     * The models and aliases available to this account.
     *
     * <pre>{@code
     * for (ModelInfo model : jev.models()) {
     *     System.out.println(model.name() + " \u2014 " + model.description());
     * }
     * // jev-latest  - The latest iteration of TypeSafe's System One Model: Jev
     * // jev-preview - A preview version of `jev-latest`: should be better in most ways
     * }</pre>
     *
     * <p>Call it when you want to pick up a model {@link JevModel} does not list yet; pass the
     * name straight through with {@link JevRequest.Builder#model(String)}.
     *
     * @return the models this key can use
     * @throws JevException on a transport failure or a non-2xx response
     */
    public List<ModelInfo> models() {
        String body = send("/v1/models", null);
        try {
            return MAPPER.readValue(body, ModelList.class).models();
        } catch (IOException e) {
            throw new JevException("could not read the model list", e);
        }
    }

    /**
     * Shuts down the underlying {@link HttpClient}. A {@code JevClient} is meant to be long-lived,
     * so close it when the application stops, not after each call — in Spring, wire it as the
     * bean's destroy method rather than opening one per request.
     */
    @Override
    public void close() {
        http.close();
    }

    private String send(String path, Object payload) {
        HttpRequest httpRequest = buildRequest(path, payload);
        IOException lastTransportFailure = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                backoff(attempt);
            }
            try {
                HttpResponse<String> response =
                        http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return response.body();
                }
                if (isRetryable(response.statusCode()) && attempt < maxRetries) {
                    continue;
                }
                throw toException(response);
            } catch (IOException e) {
                lastTransportFailure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JevException("interrupted while calling Jev", e);
            }
        }
        throw new JevException("Jev request failed after " + (maxRetries + 1) + " attempt(s)",
                lastTransportFailure);
    }

    private HttpRequest buildRequest(String path, Object payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
        if (payload == null) {
            return builder.GET().build();
        }
        try {
            return builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
        } catch (IOException e) {
            throw new JevException("could not serialize the request", e);
        }
    }

    /** 408/429 and 5xx are transient; everything else is the caller's problem. */
    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status / 100 == 5;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(retryBackoff.toMillis() * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevException("interrupted while backing off", e);
        }
    }

    private static JevException toException(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body();
        List<JevException.ValidationError> details = List.of();
        String message = "Jev request failed with HTTP " + status;
        try {
            JsonNode detail = MAPPER.readTree(body).path("detail");
            if (detail.isArray()) {
                details = MAPPER.convertValue(detail,
                        new TypeReference<List<JevException.ValidationError>>() {
                        });
                message += ": " + details.stream().map(JevException.ValidationError::msg).toList();
            } else if (detail.isTextual()) {
                message += ": " + detail.asText();
            } else if (body != null && !body.isBlank()) {
                message += ": " + truncate(body);
            }
        } catch (IOException e) {
            message += ": " + truncate(body);
        }
        return new JevException(message, status, details, null);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    /**
     * Validates the base URL up front, so a bad one fails where it was set rather than on the
     * first request with a message that never mentions {@code baseUrl}.
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "baseUrl is required, e.g. \"" + DEFAULT_BASE_URL + "\"");
        }
        String trimmed = baseUrl.strip();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL: " + trimmed, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            // "localhost:8080" parses with scheme "localhost", which is never what was meant.
            throw new IllegalArgumentException(
                    "baseUrl needs an http:// or https:// scheme: " + trimmed);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl has no host: " + trimmed);
        }
        if (uri.getPort() == 0) {
            // "http://localhost:" + port with an unassigned int gives port 0: a valid URI that
            // can never be connected to. Catch it here rather than after every retry has failed.
            throw new IllegalArgumentException(
                    "baseUrl has port 0, which is never a real server; was the port set? " + trimmed);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private record ModelList(List<ModelInfo> models) {
    }

    /** Configures a {@link JevClient}. Only {@code apiKey} is required. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = Duration.ofSeconds(30);
        private int maxRetries = 2;
        private Duration retryBackoff = Duration.ofMillis(250);
        private HttpClient httpClient;

        private Builder() {
        }

        /**
         * The TypeSafe API key. Required.
         *
         * @param apiKey the key, from the TypeSafe console or a gateway
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Point at a gateway or a test server. Defaults to {@value #DEFAULT_BASE_URL}.
         *
         * <p>This is the seam that makes the SDK testable without mocking it:
         *
         * <pre>{@code
         * JevClient jev = JevClient.builder()
         *         .apiKey("test-key")
         *         .baseUrl("http://localhost:" + mockServer.getPort())
         *         .maxRetries(0)
         *         .build();
         * }</pre>
         *
         * <p>Validated when the client is built, not when the first request goes out — so a port
         * variable that was never assigned fails here, naming the problem, instead of looking
         * valid and timing out later:
         *
         * <pre>{@code
         * int port;                                  // 0
         * .baseUrl("http://localhost:" + port)       // IllegalArgumentException: baseUrl has port 0
         * }</pre>
         *
         * @param baseUrl the API origin, with or without a trailing slash; needs an
         *                {@code http://} or {@code https://} scheme and a host
         * @return this builder
         * @throws IllegalArgumentException when the client is built, if the URL is blank, has no
         *                                  usable scheme or host, or has port 0
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Per-request timeout. Defaults to 30 seconds.
         *
         * <p>Jev answers in well under a second in normal operation, so a short timeout on a
         * user-facing path is usually right — you would rather fall back than hang a request:
         *
         * <pre>{@code
         * .timeout(Duration.ofSeconds(5))
         * }</pre>
         *
         * @param timeout the per-attempt timeout; retries each get their own
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Extra attempts after a transient failure. Defaults to 2; use 0 to disable retries.
         *
         * <p>Only {@code 408}, {@code 429}, {@code 5xx} and transport errors are retried — a
         * {@code 401} or {@code 422} is your bug and is raised immediately.
         *
         * <pre>{@code
         * .maxRetries(0)   // in tests, so a deliberate failure fails fast
         * }</pre>
         *
         * @param maxRetries extra attempts after the first; must not be negative
         * @return this builder
         * @throws IllegalArgumentException if {@code maxRetries} is negative
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Delay before the first retry; doubles per attempt. Defaults to 250ms.
         *
         * <p>With the defaults, a request that keeps failing waits 250ms, then 500ms, before
         * giving up.
         *
         * @param retryBackoff the delay before the first retry
         * @return this builder
         */
        public Builder retryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
            return this;
        }

        /**
         * Supply your own {@link HttpClient}, e.g. one configured with a proxy or an executor.
         *
         * <pre>{@code
         * .httpClient(HttpClient.newBuilder()
         *         .proxy(ProxySelector.of(new InetSocketAddress("proxy.internal", 8080)))
         *         .build())
         * }</pre>
         *
         * <p>The client you pass is closed by {@link JevClient#close()}, so do not share one you
         * still need elsewhere.
         *
         * @param httpClient the client to send requests with
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the configured client
         * @throws IllegalArgumentException if no API key was set
         */
        public JevClient build() {
            return new JevClient(this);
        }
    }
}
