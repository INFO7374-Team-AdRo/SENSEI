package com.safety.clients;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class MistralClient {

    private static final MediaType JSON = MediaType.get("application/json");
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String embedModel;

    public MistralClient(String apiKey, String baseUrl, String model, String embedModel) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.embedModel = embedModel;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build();
    }

    // Chat completion — returns the assistant message content
    public String chat(String systemPrompt, String userMessage) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);

        body.add("messages", messages);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", 2000);

        // Ask for JSON response
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);

        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Mistral API error: " + response.code() + " " + response.body().string());
            }
            String responseBody = response.body().string();
            JsonObject parsed = gson.fromJson(responseBody, JsonObject.class);
            return parsed.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
        }
    }

    // Chat without JSON format constraint
    public String chatFreeform(String systemPrompt, String userMessage) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();

        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        messages.add(user);

        body.add("messages", messages);
        body.addProperty("temperature", 0.4);
        body.addProperty("max_tokens", 4000);

        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Mistral API error: " + response.code() + " " + response.body().string());
            }
            String responseBody = response.body().string();
            JsonObject parsed = gson.fromJson(responseBody, JsonObject.class);
            return parsed.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
        }
    }

    // Embed text — returns float array
    public float[] embed(String text) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", embedModel);

        JsonArray input = new JsonArray();
        input.add(text);
        body.add("input", input);

        Request request = new Request.Builder()
            .url(baseUrl + "/embeddings")
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(gson.toJson(body), JSON))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Mistral embed error: " + response.code() + " " + response.body().string());
            }
            String responseBody = response.body().string();
            JsonObject parsed = gson.fromJson(responseBody, JsonObject.class);
            JsonArray embedding = parsed.getAsJsonArray("data")
                .get(0).getAsJsonObject()
                .getAsJsonArray("embedding");

            float[] result = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                result[i] = embedding.get(i).getAsFloat();
            }
            return result;
        }
    }
}
