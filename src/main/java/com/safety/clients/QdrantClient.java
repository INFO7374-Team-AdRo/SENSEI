package com.safety.clients;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QdrantClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String apiKey;
    private final String collection;

    public QdrantClient(String baseUrl, String apiKey, String collection) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.collection = collection;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build();
    }

    // Create collection if it doesn't exist
    public void ensureCollection(int vectorSize) throws IOException {
        JsonObject body = new JsonObject();
        JsonObject vectors = new JsonObject();
        vectors.addProperty("size", vectorSize);
        vectors.addProperty("distance", "Cosine");
        body.add("vectors", vectors);

        Request request = new Request.Builder()
            .url(baseUrl + "/collections/" + collection)
            .addHeader("api-key", apiKey)
            .put(RequestBody.create(gson.toJson(body), JSON_TYPE))
            .build();

        try (Response response = http.newCall(request).execute()) {
            // 409 = already exists, that's fine
            if (response.isSuccessful() || response.code() == 409) {
                return;
            }
            // Ignore errors for now — collection might already exist
        }
    }

    // Store a vector with payload
    public void upsert(String id, float[] vector, Map<String, Object> payload) throws IOException {
        JsonObject point = new JsonObject();
        point.addProperty("id", id);

        JsonArray vecArray = new JsonArray();
        for (float v : vector) vecArray.add(v);
        point.add("vector", vecArray);

        JsonObject payloadObj = gson.toJsonTree(payload).getAsJsonObject();
        point.add("payload", payloadObj);

        JsonObject body = new JsonObject();
        JsonArray points = new JsonArray();
        points.add(point);
        body.add("points", points);

        Request request = new Request.Builder()
            .url(baseUrl + "/collections/" + collection + "/points")
            .addHeader("api-key", apiKey)
            .put(RequestBody.create(gson.toJson(body), JSON_TYPE))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Qdrant upsert error: " + response.code());
            }
        }
    }

    // Search for similar vectors
    public List<SearchResult> search(float[] queryVector, int topK) throws IOException {
        JsonObject body = new JsonObject();

        JsonArray vecArray = new JsonArray();
        for (float v : queryVector) vecArray.add(v);
        body.add("vector", vecArray);

        body.addProperty("limit", topK);
        body.addProperty("with_payload", true);

        Request request = new Request.Builder()
            .url(baseUrl + "/collections/" + collection + "/points/search")
            .addHeader("api-key", apiKey)
            .post(RequestBody.create(gson.toJson(body), JSON_TYPE))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Qdrant search error: " + response.code());
            }

            String responseBody = response.body().string();
            JsonObject parsed = gson.fromJson(responseBody, JsonObject.class);
            JsonArray results = parsed.getAsJsonArray("result");

            List<SearchResult> searchResults = new ArrayList<>();
            for (var r : results) {
                JsonObject obj = r.getAsJsonObject();
                String id = obj.get("id").getAsString();
                double score = obj.get("score").getAsDouble();
                JsonObject payload = obj.getAsJsonObject("payload");

                searchResults.add(new SearchResult(
                    id, score,
                    payload.has("hazardClass") ? payload.get("hazardClass").getAsString() : "",
                    payload.has("rootCause") ? payload.get("rootCause").getAsString() : "",
                    payload.has("resolution") ? payload.get("resolution").getAsString() : ""
                ));
            }
            return searchResults;
        }
    }

    public record SearchResult(
        String id,
        double score,
        String hazardClass,
        String rootCause,
        String resolution
    ) {}
}
