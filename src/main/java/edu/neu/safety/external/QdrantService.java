package edu.neu.safety.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.neu.safety.model.IncidentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled HTTP client for Qdrant. We use Qdrant as the vector store for
 * the RAG side of the system — past incidents are embedded with Mistral and
 * upserted here, and for every new snapshot the retrieval agent does a
 * top-K similarity search against this collection to pull up contextually
 * similar incidents for the LLM to reason over.
 *
 * <p>The class supports both deployments we use in development:
 * <ul>
 *   <li>Local Docker container on {@code http://localhost:6333} — no auth,
 *       used on laptops.</li>
 *   <li>Qdrant Cloud on {@code https://...qdrant.io} — requires an
 *       {@code api-key} header, used for the demo cluster.</li>
 * </ul>
 *
 * <p>A full-featured official Java SDK exists, but it pulls gRPC into the
 * classpath and we only need four REST calls — keeping this lean avoids
 * version conflicts with Akka's gRPC stack.
 */
public class QdrantService {

    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String collectionName;
    private final String apiKey;

    /**
     * @param baseUrl        Qdrant REST root, with or without trailing slash
     * @param collectionName collection we read/write incident vectors into
     * @param apiKey         Cloud API key; empty string for local Qdrant
     */
    public QdrantService(String baseUrl, String collectionName, String apiKey) {
        // baseUrl is the full REST URL (e.g. "http://localhost:6333" or
        // "https://xxx.us-west-2-0.aws.cloud.qdrant.io"). Strip any trailing slash.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = collectionName;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /** Start a request builder and attach the {@code api-key} header when running against Cloud. */
    private HttpRequest.Builder authedRequest(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url));
        if (!apiKey.isEmpty()) {
            b.header("api-key", apiKey);
        }
        return b;
    }

    /**
     * Create the collection if it doesn't already exist. Cosine distance is
     * what we want here since Mistral embeddings are L2-normalized, and the
     * dimensionality is the one produced by mistral-embed (1024). Called
     * once at startup from the RetrievalAgent.
     */
    public void ensureCollection(int vectorSize) {
        try {
            HttpRequest getReq = authedRequest(baseUrl + "/collections/" + collectionName).GET().build();
            HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());

            if (getResp.statusCode() == 200) {
                log.info("Qdrant collection '{}' already exists", collectionName);
                return;
            }

            ObjectNode body = mapper.createObjectNode();
            ObjectNode vectors = mapper.createObjectNode();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            body.set("vectors", vectors);

            HttpRequest createReq = authedRequest(baseUrl + "/collections/" + collectionName)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());

            if (createResp.statusCode() == 200) {
                log.info("Created Qdrant collection '{}' (dim={})", collectionName, vectorSize);
            } else {
                log.warn("Qdrant create-collection failed: {}", createResp.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure Qdrant collection", e);
        }
    }

    /**
     * Insert (or update) a single incident point. Qdrant point IDs have to
     * be numeric, so we squash the string id through {@code hashCode()} —
     * collisions are theoretically possible but not a concern at the scale
     * of the demo dataset. The original id is stored in the payload under
     * {@code source_id} so we can always recover it.
     */
    public void upsert(String id, float[] vector, String description, String resolution) {
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode points = mapper.createArrayNode();
            ObjectNode point = mapper.createObjectNode();
            point.put("id", id.hashCode() & 0x7FFFFFFF);

            ArrayNode vectorArr = mapper.createArrayNode();
            for (float v : vector) {
                vectorArr.add(v);
            }
            point.set("vector", vectorArr);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("description", description);
            payload.put("resolution", resolution);
            payload.put("source_id", id);
            payload.put("timestamp", System.currentTimeMillis());
            point.set("payload", payload);

            points.add(point);
            body.set("points", points);

            HttpRequest request = authedRequest(baseUrl + "/collections/" + collectionName + "/points")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Qdrant upsert returned {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Qdrant upsert failed: {}", e.getMessage());
        }
    }

    /**
     * Find the {@code topK} most similar past incidents for a query
     * embedding. Returns an empty list on any failure so the reasoning
     * agent can still run without RAG context.
     */
    public List<IncidentReport.PastIncident> search(float[] vector, int topK) {
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode vectorArr = mapper.createArrayNode();
            for (float v : vector) {
                vectorArr.add(v);
            }
            body.set("vector", vectorArr);
            body.put("limit", topK);
            body.put("with_payload", true);

            HttpRequest request = authedRequest(baseUrl + "/collections/" + collectionName + "/points/search")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Qdrant search returned {}: {}", response.statusCode(), response.body());
                return List.of();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.path("result");

            List<IncidentReport.PastIncident> incidents = new ArrayList<>();
            for (JsonNode hit : results) {
                String desc = hit.path("payload").path("description").asText("");
                String res = hit.path("payload").path("resolution").asText("");
                float score = (float) hit.path("score").asDouble(0);
                incidents.add(new IncidentReport.PastIncident(desc, res, score));
            }
            return incidents;
        } catch (Exception e) {
            log.error("Qdrant search failed: {}", e.getMessage());
            return List.of();
        }
    }
}
