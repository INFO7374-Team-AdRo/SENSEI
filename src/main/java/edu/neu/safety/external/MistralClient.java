package edu.neu.safety.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Mistral chat and embeddings APIs. We talk to Mistral
 * in two modes:
 *
 * <ol>
 *   <li>Short, structured chat — system prompt + user prompt, low temperature,
 *       500 tokens. Used by the reasoning agent on every hazardous snapshot.</li>
 *   <li>Longer freeform chat for OSHA-style report generation, with a higher
 *       token cap and a slightly looser temperature.</li>
 * </ol>
 *
 * <p>The free tier of Mistral enforces a fairly aggressive 429 envelope, so
 * we:
 * <ul>
 *   <li>Rate-limit outbound requests to at most one every {@value MIN_REQUEST_INTERVAL_MS}ms.</li>
 *   <li>Retry on 429/5xx up to {@value MAX_RETRIES} times with exponential backoff.</li>
 * </ul>
 *
 * <p>Designed to be shared across agents — the underlying {@link HttpClient}
 * is thread-safe.
 */
public class MistralClient {

    private static final Logger log = LoggerFactory.getLogger(MistralClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MIN_REQUEST_INTERVAL_MS = 500;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private volatile long lastRequestTime = 0;

    /**
     * @param apiKey  Mistral API key; if empty the client will 401 on first use
     * @param baseUrl Mistral base URL (usually "https://api.mistral.ai/v1")
     * @param model   model name for chat completions (e.g. "mistral-small-latest")
     */
    public MistralClient(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** Synchronous chat — used inside actors that need a blocking call within the message handler. */
    public String chat(String systemPrompt, String userMessage) throws Exception {
        return chatInternal(systemPrompt, userMessage, 0.3, 500);
    }

    /** Long-form chat for report generation — higher token cap, slightly more creative temperature. */
    public String chatFreeform(String systemPrompt, String userMessage) throws Exception {
        return chatInternal(systemPrompt, userMessage, 0.4, 4000);
    }

    /** Async wrapper kept for callers that want to compose with futures. */
    public CompletableFuture<String> chatAsync(String systemPrompt, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return chat(systemPrompt, userMessage);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Shared implementation for both {@link #chat(String, String)} and
     * {@link #chatFreeform(String, String)}. Handles the retry loop and the
     * JSON envelope construction.
     */
    private String chatInternal(String systemPrompt, String userMessage,
                                double temperature, int maxTokens) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.set("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        String bodyStr = mapper.writeValueAsString(body);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            rateLimitWait();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .timeout(Duration.ofSeconds(45))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return extractContent(response.body());
            }

            if (isRetryable(response.statusCode()) && attempt < MAX_RETRIES) {
                long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                log.warn("Mistral chat returned {} (attempt {}/{}), retrying in {}ms",
                    response.statusCode(), attempt + 1, MAX_RETRIES, backoff);
                Thread.sleep(backoff);
            } else {
                log.error("Mistral API error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Mistral API error: " + response.statusCode());
            }
        }
        throw new RuntimeException("Mistral chat failed after " + MAX_RETRIES + " retries");
    }

    /**
     * Produce a 1024-dim embedding for the given text via Mistral's
     * {@code mistral-embed} model. Used by the retrieval agent to vectorize
     * both incoming snapshots and stored incidents before the Qdrant query.
     * Returns a zero-vector on parse failure so the pipeline degrades
     * gracefully instead of crashing.
     */
    public CompletableFuture<float[]> embed(String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", "mistral-embed");
                ArrayNode input = mapper.createArrayNode();
                input.add(text);
                body.set("input", input);

                String bodyStr = mapper.writeValueAsString(body);

                for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                    rateLimitWait();

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embeddings"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        return extractEmbedding(response.body());
                    }

                    if (isRetryable(response.statusCode()) && attempt < MAX_RETRIES) {
                        long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                        log.warn("Mistral embed returned {} (attempt {}/{}), retrying in {}ms",
                            response.statusCode(), attempt + 1, MAX_RETRIES, backoff);
                        Thread.sleep(backoff);
                    } else {
                        log.error("Mistral embed error {}: {}", response.statusCode(), response.body());
                        throw new RuntimeException("Mistral embed error: " + response.statusCode());
                    }
                }
                throw new RuntimeException("Mistral embed failed after " + MAX_RETRIES + " retries");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during Mistral embed", e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Mistral embed failed", e);
            }
        });
    }

    /**
     * Sleep just long enough to keep outbound requests below Mistral's rate
     * ceiling. Called at the start of every attempt. volatile is enough here
     * because we only ever make one request at a time per client.
     */
    private void rateLimitWait() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    /** 429 + the common 5xx transient codes are worth another attempt; anything else is fatal. */
    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
            || statusCode == 503 || statusCode == 504;
    }

    /**
     * Pull the assistant's text out of a chat-completions envelope. Falls
     * back to the raw body so at least something surfaces in logs when the
     * API returns an unexpected shape.
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("Failed to parse Mistral chat response: {}", e.getMessage());
            return responseBody;
        }
    }

    /**
     * Decode the embedding array from the API response. A 1024-float zero
     * vector is returned on failure so the Qdrant search still has a
     * well-formed input shape.
     */
    private float[] extractEmbedding(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;
        } catch (Exception e) {
            log.error("Failed to parse embedding: {}", e.getMessage());
            return new float[1024];
        }
    }
}
