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
        // Check if collection exists first
        Request checkReq = new Request.Builder()
            .url(baseUrl + "/collections/" + collection)
            .addHeader("api-key", apiKey)
            .get()
            .build();

        try (Response checkResp = http.newCall(checkReq).execute()) {
            if (checkResp.isSuccessful()) {
                return; // Collection already exists
            }
        }

        // Create new collection
        JsonObject body = new JsonObject();
        JsonObject vectors = new JsonObject();
        vectors.addProperty("size", vectorSize);
        vectors.addProperty("distance", "Cosine");
        body.add("vectors", vectors);

        Request request = new Request.Builder()
            .url(baseUrl + "/collections/" + collection)
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .put(RequestBody.create(gson.toJson(body), JSON_TYPE))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 409) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("Failed to create collection: " + response.code() + " " + err);
            }
        }
    }

    // Store a vector with payload
    public void upsert(String id, float[] vector, Map<String, Object> payload) throws IOException {
        JsonObject point = new JsonObject();
        // Qdrant needs UUID or integer ID
        point.addProperty("id", UUID.nameUUIDFromBytes(id.getBytes()).toString());

        JsonArray vecArray = new JsonArray();
        for (float v : vector) vecArray.add(v);
        point.add("vector", vecArray);

        JsonObject payloadObj = new JsonObject();
        for (var entry : payload.entrySet()) {
            if (entry.getValue() instanceof String) {
                payloadObj.addProperty(entry.getKey(), (String) entry.getValue());
            } else if (entry.getValue() instanceof Number) {
                payloadObj.addProperty(entry.getKey(), (Number) entry.getValue());
            } else {
                payloadObj.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        point.add("payload", payloadObj);

        JsonObject body = new JsonObject();
        JsonArray points = new JsonArray();
        points.add(point);
        body.add("points", points);

        Request request = new Request.Builder()
            .url(baseUrl + "/collections/" + collection + "/points")
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .put(RequestBody.create(gson.toJson(body), JSON_TYPE))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "no body";
                throw new IOException("Qdrant upsert error: " + response.code() + " " + errBody);
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