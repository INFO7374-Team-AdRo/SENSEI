package com.safety.http;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.safety.agents.OrchestratorAgent;
import com.safety.agents.RetrievalAgent;
import com.safety.agents.SensorAgent;
import com.safety.agents.ThermalAgent;
import com.safety.clients.MistralClient;
import com.safety.clients.MongoDBService;
import com.safety.protocol.InspectionProtocol;
import com.safety.protocol.LLMProtocol;
import com.safety.protocol.RetrievalProtocol;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.server.PathMatchers.segment;

public class SafetyHttpServer {

    private static final String[] SENSOR_TYPES =
        {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};

    private static SafetyHttpServer INSTANCE;

    private final ActorSystem<?> system;
    private final Gson gson = new Gson();

    // Agent refs (set after construction)
    private ActorRef<LLMProtocol.Command> llmAgent;
    private ActorRef<OrchestratorAgent.Command> orchestratorAgent;
    private ActorRef<ThermalAgent.Command> thermalAgent;
    private ClusterSharding sharding;
    private MongoDBService mongo;

    // In-memory event buffers (rolling window for live feed)
    private final List<String> recentSensorEvents = new ArrayList<>();
    private final List<String> recentEscalationEvents = new ArrayList<>();

    // Lifetime totals — never capped
    private final java.util.concurrent.atomic.AtomicLong totalSensorEvents     = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalEscalationEvents = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalIncidents        = new java.util.concurrent.atomic.AtomicLong();

    // Latest classification label from the PMML model
    private volatile String latestClassification = null;

    // Fault-tolerance tracking
    private final Map<String, Instant> killedAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastPpmBySensor = new ConcurrentHashMap<>();
    private final Map<String, Integer> killCountBySensor = new ConcurrentHashMap<>();
    private final Map<String, Long> messagesProcessed = new ConcurrentHashMap<>();

    // Thermal state — updated by ThermalAgent (real) or pushSensorEvent (initial estimate)
    private volatile double thermalTemp = 22.0;
    private volatile boolean thermalAnomaly = false;
    private volatile boolean thermalFromRealImage = false;  // true once ThermalAgent has spoken

    // Real thermal frame pushed by DataReplayStream (null = no image for current gas class)
    private volatile String currentThermalPath = null;
    private volatile String currentThermalLabel = null;  // gas class label for the current row

    // Rolling sensor history for the line chart (last 60 fused events)
    private final List<Map<String, Object>> sensorHistory = new java.util.ArrayList<>();

    // InspecSafe V1 — rolling inspection event buffer + dataset root for image serving
    private final List<Map<String, Object>> recentInspectionEvents = new ArrayList<>();
    private volatile String inspecsafeDataRoot = "./inspecsafe-v1";

    // Clients for InspecSafe report generation + Qdrant embedding
    private volatile MistralClient mistral;
    private volatile ActorRef<RetrievalAgent.Command> retrievalAgent;

    public SafetyHttpServer(ActorSystem<?> system) {
        this.system = system;
        INSTANCE = this;
    }

    public static SafetyHttpServer getInstance() { return INSTANCE; }

    public void setLlmAgent(ActorRef<LLMProtocol.Command> llmAgent) { this.llmAgent = llmAgent; }
    public void setOrchestratorAgent(ActorRef<OrchestratorAgent.Command> ref) { this.orchestratorAgent = ref; }
    public void setThermalAgent(ActorRef<ThermalAgent.Command> ref) { this.thermalAgent = ref; }
    public void setSharding(ClusterSharding sharding) { this.sharding = sharding; }
    public void setMongo(MongoDBService mongo) { this.mongo = mongo; }
    public void setInspecsafeDataRoot(String path) { this.inspecsafeDataRoot = path; }
    public void setMistral(MistralClient m) { this.mistral = m; }
    public void setRetrievalAgent(ActorRef<RetrievalAgent.Command> ref) { this.retrievalAgent = ref; }

    /**
     * Called by DataReplayStream each row.
     * relativePath = "Mixture/0_Mixture.png" when an image exists, null when no image folder for that class.
     * gasLabel = the gas class for the current CSV row (always set).
     */
    public void pushThermalFrame(String relativePath, String gasLabel) {
        this.currentThermalPath  = relativePath;
        this.currentThermalLabel = gasLabel;
    }

    /**
     * Called by ThermalAgent after analyzing an image frame (vision or simulation).
     * This overwrites the gas-sensor-based simulation so the badge reflects the actual image reading.
     */
    public void updateThermalReading(double tempCelsius, boolean anomaly) {
        this.thermalTemp        = Math.round(tempCelsius * 10.0) / 10.0;
        this.thermalAnomaly     = anomaly;
        this.thermalFromRealImage = true;
    }

    /**
     * Called by DataReplayStream on EVERY row — gives the fault page live per-sensor PPM
     * values without waiting for FusionAgent's 5-second window.
     */
    public void updateLivePpm(String sensorType, int ppm) {
        lastPpmBySensor.put(sensorType, ppm);
        messagesProcessed.merge(sensorType, 1L, Long::sum);
    }

    public void pushSensorEvent(Map<String, Object> event) {
        int breached = 0;
        int maxPpm = 0;
        for (String sensor : SENSOR_TYPES) {
            Object val = event.get(sensor);
            if (val instanceof Number) {
                int ppm = ((Number) val).intValue();
                lastPpmBySensor.put(sensor, ppm);
                int threshold = getSensorThreshold(sensor);
                // ADC drops below threshold when gas is present
                if (ppm < threshold) { breached++; maxPpm = Math.max(maxPpm, threshold - ppm); }
            }
        }
        // Only use gas-sensor simulation BEFORE ThermalAgent has provided a real reading
        if (!thermalFromRealImage) {
            int mq7Val = lastPpmBySensor.getOrDefault("MQ7", 606);
            double simTemp = 22.0 + (breached * 3.5) + (mq7Val < 560 ? 8.0 : 0)
                + (Math.random() * 1.5 - 0.75);
            thermalTemp   = Math.round(simTemp * 10.0) / 10.0;
            thermalAnomaly = breached >= 3 || simTemp > 38.0;
        }

        String json = gson.toJson(event);
        synchronized (recentSensorEvents) {
            recentSensorEvents.add(json);
            if (recentSensorEvents.size() > 100) recentSensorEvents.remove(0);
        }
        totalSensorEvents.incrementAndGet();

        // Append to sensor history (keep last 60)
        Map<String, Object> histEntry = new java.util.LinkedHashMap<>(event);
        synchronized (sensorHistory) {
            sensorHistory.add(histEntry);
            if (sensorHistory.size() > 60) sensorHistory.remove(0);
        }
    }

    /** Called by ClassificationAgent after each PMML prediction. */
    public void updateLatestClassification(String label) {
        this.latestClassification = label;
    }

    /**
     * Low-side ADC thresholds (ADC drops when gas is present).
     * Used in pushSensorEvent() to count breached sensors for thermal simulation.
     */
    private static int getSensorThreshold(String sensor) {
        return switch (sensor) {
            case "MQ2"   -> 690;
            case "MQ3"   -> 490;
            case "MQ5"   -> 400;
            case "MQ6"   -> 400;
            case "MQ7"   -> 560;
            case "MQ8"   -> 580;
            case "MQ135" -> 430;
            default      -> 400;
        };
    }

    public void pushEscalationEvent(Map<String, Object> event) {
        String json = gson.toJson(event);
        synchronized (recentEscalationEvents) {
            recentEscalationEvents.add(json);
            if (recentEscalationEvents.size() > 100) recentEscalationEvents.remove(0);
        }
        totalEscalationEvents.incrementAndGet();
    }

    /** Called by OrchestratorAgent every time an incident is finalized (any tier). */
    public void incrementTotalIncidents() {
        totalIncidents.incrementAndGet();
    }

    /**
     * Called by VisualInspectionAgent each time a new waypoint event fires.
     * Converts the InspectionEvent to a JSON-serialisable map and appends it
     * to the rolling buffer.  The frontend polls /api-inspection/events.
     */
    public void pushInspectionEvent(InspectionProtocol.InspectionEvent event) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("waypointId",   event.waypointId());
        m.put("robotId",      event.robotId());
        m.put("location",     event.location());
        m.put("environment",  event.environment().label);
        m.put("anomalyType",  event.anomalyType().label);
        m.put("anomalyCode",  event.anomalyType().datasetCode);
        m.put("safetyGrade",  event.safetyGrade().label);
        m.put("severity",     event.safetyGrade().severity);
        m.put("description",  event.description());
        // Image URL: '#' must be percent-encoded so browsers don't treat it as a fragment
        m.put("imageUrl",  event.imagePath() != null
            ? "/api-inspection/image/" + event.imagePath().replace("#", "%23")
            : null);
        // Audio WAV URL (served via /api-inspection/audio/)
        m.put("audioUrl",  event.audioPath() != null
            ? "/api-inspection/audio/" + event.audioPath().replace("#", "%23")
            : null);
        // Infrared video URL (served via /api-inspection/video/) — thermal-camera equivalent
        m.put("infraredUrl", event.infraredPath() != null
            ? "/api-inspection/video/" + event.infraredPath().replace("#", "%23")
            : null);
        // Cross-modal sensor readings from the waypoint's _sensor_*.txt (JSON format)
        m.put("coReading",    event.coReading());    // CO  PPM
        m.put("ch4Level",     event.ch4Level());     // CH4 %VOL
        m.put("o2Level",      event.o2Level());      // O2  %VOL
        m.put("h2sReading",   event.h2sReading());   // H2S PPM
        m.put("temperature",  event.temperature());  // °C
        m.put("humidity",     event.humidity());     // %RH
        m.put("soundLevel",   event.soundLevel());   // dB
        m.put("timestamp",    event.timestamp().toString());

        synchronized (recentInspectionEvents) {
            recentInspectionEvents.add(m);
            if (recentInspectionEvents.size() > 50) recentInspectionEvents.remove(0);
        }

        // Persist Grade 1 (Critical) and Grade 2 (Significant) to MongoDB,
        // generate an LLM report, and embed into Qdrant so chat can find them.
        String sev = event.safetyGrade().severity;
        if ("Critical".equals(sev) || "Significant".equals(sev)) {
            final Map<String, Object> snapshot = new java.util.LinkedHashMap<>(m);
            Thread reporter = new Thread(() -> generateInspectionReport(snapshot));
            reporter.setDaemon(true);
            reporter.start();
        }
    }

    private static final org.slf4j.Logger httpLog =
        org.slf4j.LoggerFactory.getLogger(SafetyHttpServer.class);

    /**
     * Runs on a daemon thread for Grade 1/2 inspection events.
     * 1. Saves raw event to MongoDB immediately.
     * 2. Calls Mistral to generate a structured safety report.
     * 3. Enriches the MongoDB document with report fields.
     * 4. Embeds report into Qdrant via RetrievalAgent so chat queries can surface it.
     */
    private void generateInspectionReport(Map<String, Object> m) {
        // Step 1 — persist raw event right away
        if (mongo != null) mongo.saveInspectionIncident(m);

        if (mistral == null) return;

        String incidentId  = "INSP-" + m.get("waypointId");
        String anomaly     = String.valueOf(m.getOrDefault("anomalyType",  ""));
        String grade       = String.valueOf(m.getOrDefault("safetyGrade",  ""));
        String severity    = String.valueOf(m.getOrDefault("severity",     ""));
        String location    = String.valueOf(m.getOrDefault("location",     ""));
        String env         = String.valueOf(m.getOrDefault("environment",  ""));
        String robot       = String.valueOf(m.getOrDefault("robotId",      ""));
        String description = String.valueOf(m.getOrDefault("description",  ""));
        Object co   = m.get("coReading");
        Object ch4  = m.get("ch4Level");
        Object o2   = m.get("o2Level");
        Object h2s  = m.get("h2sReading");
        Object temp = m.get("temperature");
        Object hum  = m.get("humidity");
        Object snd  = m.get("soundLevel");

        String sensorLine = buildSensorLine(co, ch4, o2, h2s, temp, hum, snd);

        String systemPrompt =
            "You are an industrial safety officer writing concise post-incident reports. " +
            "Respond ONLY with valid JSON, no markdown.";

        String userPrompt = String.format(
            "An inspection robot detected a visual safety anomaly. Write a structured incident report.\n\n" +
            "Anomaly: %s\nSafety Grade: %s (%s)\nLocation: %s\nEnvironment: %s\n" +
            "Robot: %s\nScene Description: %s\nSensor Readings at Detection: %s\n\n" +
            "Return ONLY valid JSON with these exact keys:\n" +
            "{\n" +
            " \"summary\": \"2-3 sentence description of the incident and its operational risk\",\n" +
            " \"rootCause\": \"most likely physical/mechanical/environmental cause based on the anomaly type, environment and sensor readings\",\n" +
            " \"recommendation\": \"2-3 specific corrective actions tailored to this anomaly type and environment\",\n" +
            " \"evidenceSteps\": [\n" +
            "   \"What the robot camera observed\",\n" +
            "   \"What the sensor readings indicate (reference actual values)\",\n" +
            "   \"Risk assessment based on anomaly + environment combination\",\n" +
            "   \"Automated action taken by the safety system\"\n" +
            " ],\n" +
            " \"timeline\": [\n" +
            "   {\"time\": \"T+0s\",  \"event\": \"Visual anomaly detected at %s by robot %s\"},\n" +
            "   {\"time\": \"T+2s\",  \"event\": \"Safety grade assigned: %s (%s severity)\"},\n" +
            "   {\"time\": \"T+4s\",  \"event\": \"Environmental sensors sampled: %s\"},\n" +
            "   {\"time\": \"T+6s\",  \"event\": \"Root cause determined and report generated\"},\n" +
            "   {\"time\": \"T+10s\", \"event\": \"Incident embedded into RAG store for future retrieval\"}\n" +
            " ]\n" +
            "}",
            anomaly, grade, severity, location, env, robot, description, sensorLine,
            location, robot, grade, severity, sensorLine);

        try {
            String raw = mistral.chat(systemPrompt, userPrompt);
            // Strip markdown fences if present
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
            }

            com.google.gson.JsonObject parsed = new com.google.gson.Gson().fromJson(cleaned, com.google.gson.JsonObject.class);
            String summary        = parsed.has("summary")        ? parsed.get("summary").getAsString()        : "Visual safety incident recorded.";
            String rootCause      = parsed.has("rootCause")      ? parsed.get("rootCause").getAsString()      : anomaly + " detected at " + location;
            String recommendation = parsed.has("recommendation") ? parsed.get("recommendation").getAsString() : "Enforce PPE compliance.";

            List<String> evidenceSteps = new ArrayList<>();
            if (parsed.has("evidenceSteps") && parsed.get("evidenceSteps").isJsonArray()) {
                parsed.getAsJsonArray("evidenceSteps")
                      .forEach(el -> evidenceSteps.add(el.getAsString()));
            }
            // Fallback evidence steps if LLM didn't return them
            if (evidenceSteps.isEmpty()) {
                evidenceSteps.add("Visual anomaly '" + anomaly + "' detected by robot " + robot + " at " + location);
                evidenceSteps.add("Safety grade assigned: " + grade + " (" + severity + ") — " + description);
                evidenceSteps.add("Environmental sensors sampled: " + sensorLine);
                evidenceSteps.add("Incident report generated and embedded into RAG store");
            }

            List<Map<String, String>> timeline = new ArrayList<>();
            if (parsed.has("timeline") && parsed.get("timeline").isJsonArray()) {
                parsed.getAsJsonArray("timeline").forEach(el -> {
                    com.google.gson.JsonObject t = el.getAsJsonObject();
                    Map<String, String> entry = new java.util.LinkedHashMap<>();
                    entry.put("time",  t.has("time")  ? t.get("time").getAsString()  : "");
                    entry.put("event", t.has("event") ? t.get("event").getAsString() : "");
                    timeline.add(entry);
                });
            }

            // Build affectedSensors from env threshold breaches
            List<String> affectedSensors = new ArrayList<>();
            if (co  instanceof Number && ((Number)co).doubleValue()  > 10.0)  affectedSensors.add("CO");
            if (h2s instanceof Number && ((Number)h2s).doubleValue() > 1.0)   affectedSensors.add("H2S");
            if (ch4 instanceof Number && ((Number)ch4).doubleValue() > 0.1)   affectedSensors.add("CH4");
            if (o2  instanceof Number) {
                double o2v = ((Number)o2).doubleValue();
                if (o2v < 19.5 || o2v > 23.0) affectedSensors.add("O2");
            }
            if (temp instanceof Number && ((Number)temp).doubleValue() > 40.0) affectedSensors.add("Temperature");

            // Step 2 — enrich MongoDB document with report fields
            if (mongo != null) {
                Map<String, Object> enriched = new java.util.LinkedHashMap<>(m);
                enriched.put("summary",         summary);
                enriched.put("rootCause",       rootCause);
                enriched.put("recommendation",  recommendation);
                enriched.put("evidenceSteps",   evidenceSteps);
                enriched.put("timeline",        timeline);
                enriched.put("affectedSensors", affectedSensors);
                enriched.put("generatedAt",     Instant.now().toString());
                mongo.saveInspectionIncident(enriched);
            }

            // Step 3 — embed into Qdrant via RetrievalAgent so chat can find it
            if (retrievalAgent != null) {
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("source",      "inspecsafe");
                meta.put("anomaly",     anomaly);
                meta.put("grade",       grade);
                meta.put("location",    location);
                meta.put("environment", env);
                meta.put("timestamp",   m.getOrDefault("timestamp", ""));
                retrievalAgent.tell(new RetrievalAgent.Store(
                    new RetrievalProtocol.StoreIncident(incidentId, summary + " " + rootCause, meta)));
            }

            httpLog.info("Inspection report generated & embedded — id={} anomaly={} grade={}", incidentId, anomaly, grade);

        } catch (Exception e) {
            httpLog.warn("Inspection report generation failed for {}: {}", incidentId, e.getMessage());
        }
    }

    private String buildSensorLine(Object co, Object ch4, Object o2, Object h2s,
                                    Object temp, Object hum, Object snd) {
        List<String> parts = new ArrayList<>();
        if (co   != null) parts.add(String.format("CO=%.1f ppm",     toDouble(co)));
        if (ch4  != null) parts.add(String.format("CH4=%.3f %%VOL",  toDouble(ch4)));
        if (o2   != null) parts.add(String.format("O2=%.1f %%VOL",   toDouble(o2)));
        if (h2s  != null) parts.add(String.format("H2S=%.1f ppm",    toDouble(h2s)));
        if (temp != null) parts.add(String.format("Temp=%.1f°C",     toDouble(temp)));
        if (hum  != null) parts.add(String.format("Humidity=%.0f%%RH", toDouble(hum)));
        if (snd  != null) parts.add(String.format("Sound=%.0f dB",   toDouble(snd)));
        return parts.isEmpty() ? "not available" : String.join(", ", parts);
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private Route jsonResponse(String json) {
        return complete(HttpResponse.create()
            .withStatus(StatusCodes.OK)
            .withEntity(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, json));
    }

    private Route jsonNotFound(String msg) {
        return complete(HttpResponse.create()
            .withStatus(StatusCodes.NOT_FOUND)
            .withEntity(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
                "{\"error\":\"" + msg + "\"}"));
    }

    public Route createRoutes() {
        return respondWithHeaders(
            List.of(
                HttpHeader.parse("Access-Control-Allow-Origin", "*"),
                HttpHeader.parse("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
                HttpHeader.parse("Access-Control-Allow-Headers", "Content-Type")
            ),
            () -> concat(
                options(() -> complete(StatusCodes.OK)),

                pathEndOrSingleSlash(() ->
                    jsonResponse("{\"message\":\"Industrial Safety Monitoring API\"}")),

                pathPrefix("health", () ->
                    pathEndOrSingleSlash(() -> jsonResponse("{\"status\":\"ok\"}"))),

                // --- Serve thermal images from disk ---
                pathPrefix("thermal-images", () ->
                    getFromDirectory("data/Thermal Camera Images")
                ),

                // --- Sensor / escalation events ---
                pathPrefix("api-sensors", () ->
                    pathEndOrSingleSlash(() -> {
                        String json;
                        synchronized (recentSensorEvents) {
                            json = gson.toJson(new ArrayList<>(recentSensorEvents));
                        }
                        return jsonResponse(json);
                    })
                ),

                pathPrefix("api-escalations", () ->
                    pathEndOrSingleSlash(() -> {
                        String json;
                        synchronized (recentEscalationEvents) {
                            json = gson.toJson(new ArrayList<>(recentEscalationEvents));
                        }
                        return jsonResponse(json);
                    })
                ),

                // --- Thermal: GET status + current frame URL; POST /analyze ---
                pathPrefix("api-thermal", () ->
                    concat(
                        // POST /api-thermal/analyze — forward base64 image to ThermalAgent vision
                        path("analyze", () ->
                            post(() ->
                                extractRequest(req ->
                                    onSuccess(req.entity().toStrict(10_000_000, system), strict -> {
                                        String body = strict.getData().utf8String();
                                        String base64 = "";
                                        String mimeType = "image/jpeg";
                                        try {
                                            JsonObject j = gson.fromJson(body, JsonObject.class);
                                            if (j.has("image"))    base64   = j.get("image").getAsString();
                                            if (j.has("mimeType")) mimeType = j.get("mimeType").getAsString();
                                        } catch (Exception ignored) {}

                                        if (thermalAgent == null) {
                                            return jsonResponse("{\"error\":\"ThermalAgent not available\"}");
                                        }
                                        final String fb64 = base64, fmime = mimeType;
                                        CompletionStage<ThermalAgent.ThermalAnalysisResult> future =
                                            AskPattern.ask(thermalAgent,
                                                replyTo -> new ThermalAgent.AnalyzeThermalImage(fb64, fmime, replyTo),
                                                Duration.ofSeconds(30), system.scheduler());
                                        return onSuccess(future, result -> {
                                            Map<String, Object> out = new java.util.LinkedHashMap<>();
                                            out.put("maxTemperature", result.maxTemperature());
                                            out.put("hotspots",       result.hotspots());
                                            out.put("anomalyDetected",result.anomalyDetected());
                                            out.put("description",    result.description());
                                            out.put("severity",       result.severity());
                                            return jsonResponse(gson.toJson(out));
                                        });
                                    })
                                )
                            )
                        ),

                        // GET /api-thermal — current frame URL + simulated temp
                        pathEndOrSingleSlash(() -> {
                            Map<String, Object> thermal = new java.util.LinkedHashMap<>();
                            thermal.put("temperature", thermalTemp);
                            thermal.put("anomaly", thermalAnomaly);
                            thermal.put("unit", "celsius");
                            thermal.put("currentLabel", currentThermalLabel);
                            thermal.put("hasImage", currentThermalPath != null);
                            thermal.put("currentFrameUrl",
                                currentThermalPath != null
                                    ? "/thermal-images/" + currentThermalPath
                                    : null);
                            return jsonResponse(gson.toJson(thermal));
                        })
                    )
                ),

                // Sensor history for line chart (last 60 fused readings)
                pathPrefix("api-sensor-history", () ->
                    pathEndOrSingleSlash(() -> {
                        List<Map<String, Object>> snap;
                        synchronized (sensorHistory) { snap = new java.util.ArrayList<>(sensorHistory); }
                        return jsonResponse(gson.toJson(snap));
                    })
                ),

                // ── Unified streaming endpoint — one poll replaces six ──────────────
                // Returns everything the dashboard needs in a single JSON document so
                // the frontend can use a single 2-second interval instead of separate
                // polls at different rates (1s sensors, 5s inspections, etc.).
                pathPrefix("api-events", () ->
                    pathEndOrSingleSlash(() ->
                        get(() -> {
                            Map<String, Object> resp = new java.util.LinkedHashMap<>();

                            // Latest sensor reading (single map, not the raw JSON string)
                            synchronized (recentSensorEvents) {
                                if (!recentSensorEvents.isEmpty()) {
                                    String raw = recentSensorEvents.get(recentSensorEvents.size() - 1);
                                    resp.put("latestSensor", gson.fromJson(raw, Map.class));
                                }
                            }

                            // Recent gas escalations (last 50, oldest→newest for the chart)
                            List<Object> escList = new java.util.ArrayList<>();
                            synchronized (recentEscalationEvents) {
                                int start = Math.max(0, recentEscalationEvents.size() - 50);
                                for (int i = start; i < recentEscalationEvents.size(); i++) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> ev = gson.fromJson(recentEscalationEvents.get(i), Map.class);
                                    ev.put("kind", "escalation");
                                    escList.add(ev);
                                }
                            }
                            resp.put("escalations", escList);

                            // Visual inspection events (newest first, last 50)
                            List<Map<String, Object>> inspSnap;
                            synchronized (recentInspectionEvents) {
                                inspSnap = new java.util.ArrayList<>(recentInspectionEvents);
                            }
                            java.util.Collections.reverse(inspSnap);
                            resp.put("inspections", inspSnap);

                            // Thermal camera state
                            Map<String, Object> thermalMap = new java.util.LinkedHashMap<>();
                            thermalMap.put("temperature",    thermalTemp);
                            thermalMap.put("anomaly",        thermalAnomaly);
                            thermalMap.put("unit",           "celsius");
                            thermalMap.put("currentLabel",   currentThermalLabel);
                            thermalMap.put("hasImage",       currentThermalPath != null);
                            thermalMap.put("currentFrameUrl",
                                currentThermalPath != null ? "/thermal-images/" + currentThermalPath : null);
                            resp.put("thermal", thermalMap);

                            // System status
                            Map<String, Object> statusMap = new java.util.LinkedHashMap<>();
                            statusMap.put("sensorShards",           7);
                            statusMap.put("recentSensorEvents",     recentSensorEvents.size());
                            statusMap.put("recentEscalationEvents", recentEscalationEvents.size());
                            statusMap.put("totalSensorEvents",      totalSensorEvents.get());
                            statusMap.put("totalEscalationEvents",  totalEscalationEvents.get());
                            statusMap.put("totalIncidents",         totalIncidents.get());
                            statusMap.put("latestClassification",   latestClassification);
                            statusMap.put("clusterNode",            "127.0.0.1:2551");
                            statusMap.put("mongoAvailable",         mongo != null && mongo.isAvailable());
                            resp.put("status", statusMap);

                            // Sensor history for the trend chart
                            List<Map<String, Object>> histSnap;
                            synchronized (sensorHistory) { histSnap = new java.util.ArrayList<>(sensorHistory); }
                            resp.put("sensorHistory", histSnap);

                            return jsonResponse(gson.toJson(resp));
                        })
                    )
                ),

                pathPrefix("api-status", () ->
                    pathEndOrSingleSlash(() -> {
                        Map<String, Object> status = new java.util.LinkedHashMap<>();
                        status.put("sensorShards",            7);
                        status.put("recentSensorEvents",      recentSensorEvents.size());
                        status.put("recentEscalationEvents",  recentEscalationEvents.size());
                        status.put("totalSensorEvents",       totalSensorEvents.get());
                        status.put("totalEscalationEvents",   totalEscalationEvents.get());
                        status.put("totalIncidents",          totalIncidents.get());
                        status.put("latestClassification",    latestClassification);
                        status.put("clusterNode",             "127.0.0.1:2551");
                        status.put("mongoAvailable",          mongo != null && mongo.isAvailable());
                        return jsonResponse(gson.toJson(status));
                    })
                ),

                // --- Incidents list (MongoDB primary, in-memory fallback) ---
                pathPrefix("api-incidents", () ->
                    pathEndOrSingleSlash(() ->
                        get(() -> {
                            if (mongo != null && mongo.isAvailable()) {
                                CompletionStage<List<Map<String, Object>>> future =
                                    CompletableFuture.supplyAsync(mongo::getAllIncidents);
                                return onSuccess(future, docs -> jsonResponse(gson.toJson(docs)));
                            }
                            if (orchestratorAgent == null) return jsonResponse("[]");
                            CompletionStage<OrchestratorAgent.IncidentListResponse> future =
                                AskPattern.ask(orchestratorAgent,
                                    OrchestratorAgent.GetIncidents::new,
                                    Duration.ofSeconds(5), system.scheduler());
                            return onSuccess(future, resp -> {
                                List<Map<String, Object>> result = resp.incidents().stream()
                                    .map(this::incidentToMap).collect(Collectors.toList());
                                return jsonResponse(gson.toJson(result));
                            });
                        })
                    )
                ),

                // --- Report by ID ---
                path(segment("api-report").slash(segment()), incidentId ->
                    get(() -> {
                        if (mongo != null && mongo.isAvailable()) {
                            CompletionStage<Map<String, Object>> future =
                                CompletableFuture.supplyAsync(() -> mongo.getIncidentById(incidentId));
                            return onSuccess(future, doc -> {
                                if (doc != null) return jsonResponse(gson.toJson(doc));
                                if (orchestratorAgent == null) return jsonNotFound("not found");
                                return jsonNotFound("Incident not found in MongoDB");
                            });
                        }
                        if (orchestratorAgent == null) return jsonNotFound("not found");
                        CompletionStage<OrchestratorAgent.IncidentResponse> future =
                            AskPattern.ask(orchestratorAgent,
                                replyTo -> new OrchestratorAgent.GetIncidentById(incidentId, replyTo),
                                Duration.ofSeconds(5), system.scheduler());
                        return onSuccess(future, resp -> {
                            if (resp.incident() == null) return jsonNotFound("Incident not found");
                            return jsonResponse(gson.toJson(buildReportFromMemory(resp.incident())));
                        });
                    })
                ),

                // --- NL chat (POST /api-chat) + history + conversations ---
                pathPrefix("api-chat", () ->
                    concat(
                        // GET /api-chat/conversations — list all conversations (sidebar)
                        path("conversations", () ->
                            get(() -> {
                                if (mongo != null && mongo.isAvailable()) {
                                    List<Map<String, Object>> convs = mongo.listConversations(50);
                                    return jsonResponse(gson.toJson(convs));
                                }
                                return jsonResponse("[]");
                            })
                        ),
                        // GET /api-chat/history/{conversationId} — load past turns for a session
                        path(segment("history").slash(segment()), convId ->
                            get(() -> {
                                if (mongo != null && mongo.isAvailable()) {
                                    List<Map<String, Object>> hist = mongo.getChatHistory(convId, 100);
                                    return jsonResponse(gson.toJson(hist));
                                }
                                return jsonResponse("[]");
                            })
                        ),
                        // POST /api-chat — send a query
                        pathEndOrSingleSlash(() ->
                            post(() ->
                                extractRequest(req ->
                                    onSuccess(req.entity().toStrict(5000, system), strict -> {
                                        String body = strict.getData().utf8String();
                                        String query = "What is the current system status?";
                                        String conversationId = "default";
                                        try {
                                            JsonObject json = gson.fromJson(body, JsonObject.class);
                                            if (json.has("query"))          query          = json.get("query").getAsString();
                                            if (json.has("conversationId")) conversationId = json.get("conversationId").getAsString();
                                        } catch (Exception ignored) {}

                                        if (llmAgent == null) {
                                            return jsonResponse("{\"answer\":\"LLM agent not available\",\"queryType\":\"error\",\"sourcesUsed\":[]}");
                                        }
                                        final String fq = query, fc = conversationId;
                                        final String liveContext = buildLiveContext();
                                        CompletionStage<LLMProtocol.NLResponse> future =
                                            AskPattern.ask(llmAgent,
                                                replyTo -> new LLMProtocol.NLQuery(fq, fc, liveContext, replyTo),
                                                Duration.ofSeconds(30), system.scheduler());
                                        return onSuccess(future, nlResp -> {
                                            Map<String, Object> out = new java.util.HashMap<>();
                                            out.put("answer",      nlResp.answer());
                                            out.put("queryType",   nlResp.queryType());
                                            out.put("sourcesUsed", nlResp.sourcesUsed());
                                            // Attach media URLs if query is inspection/media related
                                            boolean isMediaQuery = fq.toLowerCase()
                                                .matches(".*\\b(show|image|photo|picture|camera|visual|inspection|audio|sound|ir|infrared|video|feed|last|latest)\\b.*");
                                            if (isMediaQuery) {
                                                synchronized (recentInspectionEvents) {
                                                    if (!recentInspectionEvents.isEmpty()) {
                                                        Map<String, Object> ev = recentInspectionEvents
                                                            .get(recentInspectionEvents.size() - 1);
                                                        if (ev.get("imageUrl")    != null) out.put("imageUrl",    ev.get("imageUrl"));
                                                        if (ev.get("audioUrl")    != null) out.put("audioUrl",    ev.get("audioUrl"));
                                                        if (ev.get("infraredUrl") != null) out.put("infraredUrl", ev.get("infraredUrl"));
                                                    }
                                                }
                                            }
                                            return jsonResponse(gson.toJson(out));
                                        });
                                    })
                                )
                            )
                        )
                    )
                ),

                // --- InspecSafe V1 visual inspection ---
                pathPrefix("api-inspection", () ->
                    concat(
                        // GET /api-inspection/events — recent waypoint events (newest-first)
                        path("events", () ->
                            get(() -> {
                                List<Map<String, Object>> snap;
                                synchronized (recentInspectionEvents) {
                                    snap = new ArrayList<>(recentInspectionEvents);
                                }
                                Collections.reverse(snap); // newest first for the UI
                                return jsonResponse(gson.toJson(snap));
                            })
                        ),
                        // GET /api-inspection/image/<path> — serve real InspecSafe .jpg frames.
                        // getFromDirectory handles URL-decoding (%23→#) and nested paths correctly
                        // on all platforms. Akka HTTP resolves the unmatched URI suffix against
                        // the directory root, so we never need to manipulate the path ourselves.
                        pathPrefix("image", () ->
                            getFromDirectory(new java.io.File(inspecsafeDataRoot).getAbsolutePath())
                        ),
                        // GET /api-inspection/audio/<path> — serve InspecSafe WAV files
                        pathPrefix("audio", () ->
                            getFromDirectory(new java.io.File(inspecsafeDataRoot).getAbsolutePath())
                        ),
                        // GET /api-inspection/video/<path> — serve InspecSafe infrared MP4 files
                        pathPrefix("video", () ->
                            getFromDirectory(new java.io.File(inspecsafeDataRoot).getAbsolutePath())
                        )
                    )
                ),

                // --- Fault tolerance ---
                pathPrefix("api-fault", () ->
                    concat(
                        path("status", () ->
                            get(() -> {
                                Map<String, Object> shards = new java.util.LinkedHashMap<>();
                                for (String sensor : SENSOR_TYPES) {
                                    Map<String, Object> info = new java.util.HashMap<>();
                                    Instant killed = killedAt.get(sensor);
                                    String status;
                                    if (killed == null) {
                                        status = "alive";
                                    } else {
                                        long ms = Duration.between(killed, Instant.now()).toMillis();
                                        // Wide windows so the UI polls catch all phases:
                                        // 0-2s  → "killed"     (shard is stopped, messages buffered)
                                        // 2-6s  → "recovering" (Akka re-created entity, draining buffer)
                                        // 6s+   → "alive"      (fully back online)
                                        if (ms < 2000)      status = "killed";
                                        else if (ms < 6000) status = "recovering";
                                        else {
                                            status = "alive";
                                            killedAt.remove(sensor);
                                        }
                                    }
                                    info.put("status", status);
                                    info.put("lastPpm", lastPpmBySensor.getOrDefault(sensor, 0));
                                    info.put("msgCount", messagesProcessed.getOrDefault(sensor, 0L));
                                    info.put("killCount", killCountBySensor.getOrDefault(sensor, 0));
                                    if (killed != null) info.put("killedAt", killed.toString());
                                    shards.put(sensor, info);
                                }
                                return jsonResponse(gson.toJson(Map.of("shards", shards)));
                            })
                        ),
                        path(segment("kill").slash(segment()), sensorType ->
                            post(() -> {
                                if (sharding == null) {
                                    return jsonResponse("{\"error\":\"sharding not available\"}");
                                }
                                sharding.entityRefFor(SensorAgent.ENTITY_KEY, sensorType)
                                    .tell(new SensorAgent.Stop());
                                killedAt.put(sensorType, Instant.now());
                                killCountBySensor.merge(sensorType, 1, Integer::sum);
                                system.log().warn("FAULT DEMO: SensorAgent [{}] killed (kill #{})",
                                    sensorType, killCountBySensor.get(sensorType));
                                Map<String, Object> resp = new java.util.HashMap<>();
                                resp.put("killed", sensorType);
                                resp.put("timestamp", Instant.now().toString());
                                resp.put("note", "Akka Cluster Sharding will auto-restart on next message (~200ms)");
                                return jsonResponse(gson.toJson(resp));
                            })
                        )
                    )
                )
            )
        );
    }

    // ---- helpers ----

    private Map<String, Object> incidentToMap(com.safety.protocol.OrchestratorProtocol.IncidentFinalized inc) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("incidentId",     inc.incidentId());
        m.put("hazardType",     inc.reasoning().hazardType());
        m.put("severity",       inc.reasoning().severity());
        m.put("recommendation", inc.reasoning().recommendation());
        m.put("confidence",     inc.classification().confidence());
        m.put("tier",           inc.decision().tier().name());
        m.put("timestamp",      inc.decision().timestamp().toString());
        m.put("evidenceSteps",  inc.reasoning().evidenceSteps());
        return m;
    }

    private Map<String, Object> buildReportFromMemory(
            com.safety.protocol.OrchestratorProtocol.IncidentFinalized inc) {
        List<String> affectedSensors = new ArrayList<>();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().sensorEvents() != null) {
            inc.classification().fusedEvent().sensorEvents().forEach((type, event) -> {
                if (event.thresholdBreached()) affectedSensors.add(type);
            });
        }
        List<Map<String, String>> timeline = new ArrayList<>();
        var steps = inc.reasoning().evidenceSteps();
        for (int i = 0; i < steps.size(); i++) {
            timeline.add(Map.of("time", "T+" + (i * 5) + "s", "event", steps.get(i)));
        }
        if (timeline.isEmpty()) {
            timeline.add(Map.of("time", "T+0s", "event", inc.reasoning().causalChain()));
        }
        Map<String, Object> report = new java.util.HashMap<>();
        // Full sensor snapshot
        Map<String, Object> sensorReadings = new LinkedHashMap<>();
        String[] sensorOrder = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().sensorEvents() != null) {
            var events = inc.classification().fusedEvent().sensorEvents();
            for (String s : sensorOrder) {
                var ev = events.get(s);
                if (ev != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("adc",      ev.valuePpm());
                    r.put("breached", ev.thresholdBreached());
                    r.put("trend",    ev.trendShape());
                    sensorReadings.put(s, r);
                }
            }
        }

        // Thermal snapshot
        Map<String, Object> thermalData = new LinkedHashMap<>();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().thermalEvent() != null) {
            var th = inc.classification().fusedEvent().thermalEvent();
            thermalData.put("temperature", Math.round(th.maxTemperature() * 10.0) / 10.0);
            thermalData.put("anomaly",     th.anomalyDetected());
        }

        report.put("incidentId",      inc.incidentId());
        report.put("summary",         inc.reasoning().explanation());
        report.put("rootCause",       inc.reasoning().causalChain());
        report.put("affectedSensors", affectedSensors);
        report.put("sensorReadings",  sensorReadings);
        report.put("thermalData",     thermalData);
        report.put("timeline",        timeline);
        report.put("evidenceSteps",   inc.reasoning().evidenceSteps());
        report.put("recommendation",  inc.reasoning().recommendation());
        report.put("generatedAt",     Instant.now().toString());
        return report;
    }

    /**
     * Builds a concise snapshot of the current system state to inject into every chat query
     * as grounding context, preventing the LLM from hallucinating zones or personnel.
     */
    private String buildLiveContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Current UTC time: ").append(Instant.now()).append("\n");
        sb.append("Latest gas classification: ")
          .append(latestClassification != null ? latestClassification : "unknown").append("\n");
        sb.append("Temperature: ").append(String.format("%.1f°C", thermalTemp))
          .append(" (thermal anomaly=").append(thermalAnomaly).append(")\n");

        // Live sensor ADC readings with thresholds and breach status
        // MQ sensors output lower ADC when gas is present; breach = ADC < threshold
        sb.append("Sensor ADC readings (breach = ADC drops BELOW threshold):\n");
        String[] sensors   = {"MQ2",  "MQ3",  "MQ5",  "MQ6",  "MQ7",  "MQ8",  "MQ135"};
        String[] labels    = {"Flammable/Smoke","Alcohol","LPG","Butane","CO","Hydrogen","Air Quality"};
        int[]    baselines = { 748,    529,    431,    425,    606,    637,    474 };
        for (int i = 0; i < sensors.length; i++) {
            int val       = lastPpmBySensor.getOrDefault(sensors[i], -1);
            int threshold = getSensorThreshold(sensors[i]);
            if (val >= 0) {
                boolean breached = val < threshold;
                sb.append("  ").append(sensors[i]).append(" (").append(labels[i]).append(")")
                  .append(": ADC=").append(val)
                  .append(", threshold=").append(threshold)
                  .append(", baseline=").append(baselines[i])
                  .append(breached ? "  ← BREACHED (gas detected)" : "  ← OK (clean air)").append("\n");
            }
        }

        // Recent escalations (last 5)
        sb.append("Recent gas escalation events (up to 5):\n");
        synchronized (recentEscalationEvents) {
            int start = Math.max(0, recentEscalationEvents.size() - 5);
            for (int i = start; i < recentEscalationEvents.size(); i++) {
                try {
                    JsonObject e = gson.fromJson(recentEscalationEvents.get(i), JsonObject.class);
                    sb.append("  [").append(e.has("tier") ? e.get("tier").getAsString() : "?").append("] ")
                      .append(e.has("hazardType") ? e.get("hazardType").getAsString() : "?").append(" — ")
                      .append(e.has("severity") ? e.get("severity").getAsString() : "?").append(" — ")
                      .append(e.has("timestamp") ? e.get("timestamp").getAsString() : "?").append("\n");
                } catch (Exception ignored) {}
            }
        }

        // Recent visual inspection events (last 5, newest first)
        sb.append("Recent visual inspection events (up to 5, newest first):\n");
        synchronized (recentInspectionEvents) {
            int start = Math.max(0, recentInspectionEvents.size() - 5);
            for (int i = recentInspectionEvents.size() - 1; i >= start; i--) {
                try {
                    Map<String, Object> ev = recentInspectionEvents.get(i);
                    sb.append("  [").append(ev.getOrDefault("safetyGrade", "?")).append("] ")
                      .append(ev.getOrDefault("anomalyType", "?")).append(" — ")
                      .append(ev.getOrDefault("location", "?")).append(" — ")
                      .append(ev.getOrDefault("environment", "?"));
                    // Include sensor readings if present
                    Object co  = ev.get("coReading");
                    Object ch4 = ev.get("ch4Level");
                    Object o2  = ev.get("o2Level");
                    Object h2s = ev.get("h2sReading");
                    Object tmp = ev.get("temperature");
                    if (co != null || ch4 != null || o2 != null || h2s != null || tmp != null) {
                        sb.append(" | sensors:");
                        if (co  != null) sb.append(String.format(" CO=%.1f ppm",     toDouble(co)));
                        if (ch4 != null) sb.append(String.format(" CH4=%.3f %%VOL",  toDouble(ch4)));
                        if (o2  != null) sb.append(String.format(" O2=%.1f %%VOL",   toDouble(o2)));
                        if (h2s != null) sb.append(String.format(" H2S=%.1f ppm",    toDouble(h2s)));
                        if (tmp != null) sb.append(String.format(" Temp=%.1f°C",     toDouble(tmp)));
                    }
                    sb.append(" — ").append(ev.getOrDefault("timestamp", "?")).append("\n");
                } catch (Exception ignored) {}
            }
        }

        sb.append("Total sensor events processed: ").append(totalSensorEvents.get()).append("\n");
        sb.append("Total incidents logged: ").append(totalIncidents.get()).append("\n");
        sb.append("Total T2/T3 alerts: ").append(totalEscalationEvents.get()).append("\n");
        sb.append("Total visual inspection events: ").append(recentInspectionEvents.size()).append(" (recent buffer)\n");
        return sb.toString();
    }

    public void start(String host, int port) {
        Http.get(system).newServerAt(host, port)
            .bind(createRoutes())
            .whenComplete((binding, err) -> {
                if (err != null) system.log().error("HTTP server failed: {}", err.getMessage());
                else system.log().info("HTTP server started at http://{}:{}", host, port);
            });
    }
}
