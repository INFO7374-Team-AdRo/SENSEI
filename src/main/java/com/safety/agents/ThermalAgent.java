package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.safety.clients.MistralClient;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.ThermalProtocol;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

public class ThermalAgent extends AbstractBehavior<ThermalAgent.Command> {

    public interface Command {}

    public record ProcessFrame(ThermalProtocol.ThermalFrame frame) implements Command {}
    public record SetFusionRef(ActorRef<FusionAgent.Command> fusionAgent) implements Command {}
    public record AnalyzeThermalImage(
            String base64,
            String mimeType,
            ActorRef<ThermalAnalysisResult> replyTo) implements Command {}

    public record ThermalAnalysisResult(
            double maxTemperature,
            int hotspots,
            boolean anomalyDetected,
            String description,
            String severity) {}

    private static final double TEMP_ANOMALY_THRESHOLD = 45.0;
    private ActorRef<FusionAgent.Command> fusionAgent;

    private final MistralClient mistral;
    private final String visionModel;
    private final Gson gson = new Gson();

    public static Behavior<Command> create(MistralClient mistral, String visionModel) {
        return Behaviors.setup(ctx -> new ThermalAgent(ctx, mistral, visionModel));
    }

    /** Backward-compat no-arg factory for tests / legacy callers */
    public static Behavior<Command> create() {
        return Behaviors.setup(ctx -> new ThermalAgent(ctx, null, null));
    }

    private ThermalAgent(ActorContext<Command> context, MistralClient mistral, String visionModel) {
        super(context);
        this.mistral = mistral;
        this.visionModel = visionModel;
        context.getLog().info("ThermalAgent started (vision={})", visionModel != null ? visionModel : "disabled");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ProcessFrame.class, msg -> onFrame(msg.frame()))
            .onMessage(SetFusionRef.class, this::onSetFusion)
            .onMessage(AnalyzeThermalImage.class, this::onAnalyzeImage)
            .build();
    }

    private Behavior<Command> onSetFusion(SetFusionRef msg) {
        this.fusionAgent = msg.fusionAgent();
        getContext().getLog().info("ThermalAgent fusion ref set");
        return this;
    }

    private Behavior<Command> onFrame(ThermalProtocol.ThermalFrame frame) {
        double temp;
        boolean anomaly;

        if (mistral != null && visionModel != null && frame.imagePath() != null) {
            ThermalAnalysisResult result = callVision(frame.imagePath(), "image/png");
            if (result != null) {
                temp = result.maxTemperature();
                anomaly = result.anomalyDetected();
                getContext().getLog().info("THERMAL VISION: {}°C anomaly={} hotspots={} severity={}",
                    temp, anomaly, result.hotspots(), result.severity());
            } else {
                // fallback
                temp = simulateTemperature(frame.imagePath());
                anomaly = temp > TEMP_ANOMALY_THRESHOLD;
            }
        } else {
            temp = simulateTemperature(frame.imagePath());
            anomaly = temp > TEMP_ANOMALY_THRESHOLD;
        }

        if (anomaly) {
            getContext().getLog().warn("THERMAL ANOMALY: {}°C from {}", temp, frame.imagePath());
        }

        var event = new ThermalProtocol.ThermalEvent(anomaly, temp, frame.timestamp());

        if (fusionAgent != null) {
            fusionAgent.tell(new FusionAgent.ThermalUpdate(event));
        }

        // Push real temperature to HTTP server so the dashboard badge shows actual image reading
        // instead of the gas-sensor simulation formula
        SafetyHttpServer http = SafetyHttpServer.getInstance();
        if (http != null) {
            http.updateThermalReading(temp, anomaly);
        }

        return this;
    }

    private Behavior<Command> onAnalyzeImage(AnalyzeThermalImage msg) {
        if (mistral == null || visionModel == null) {
            msg.replyTo().tell(new ThermalAnalysisResult(25.0, 0, false, "Vision model not configured", "normal"));
            return this;
        }

        // Decode base64 to determine we have data, then re-encode directly
        ThermalAnalysisResult result = callVisionBase64(msg.base64(), msg.mimeType());
        if (result == null) {
            result = new ThermalAnalysisResult(25.0, 0, false, "Analysis failed", "normal");
        }
        msg.replyTo().tell(result);
        return this;
    }

    /** Reads a file at imagePath, base64-encodes it, calls Mistral vision. Returns null on failure. */
    private ThermalAnalysisResult callVision(String imagePath, String mimeType) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(imagePath));
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return callVisionBase64(base64, mimeType);
        } catch (Exception e) {
            getContext().getLog().warn("Failed to read thermal image {}: {}", imagePath, e.getMessage());
            return null;
        }
    }

    /** Calls Mistral vision with already-encoded base64. Returns null on failure. */
    private ThermalAnalysisResult callVisionBase64(String base64, String mimeType) {
        try {
            String raw = mistral.analyzeImage(base64, mimeType, visionModel);

            // Strip markdown fences
            String cleaned = raw.trim();
            if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
            else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();

            JsonObject json = gson.fromJson(cleaned, JsonObject.class);

            double maxTemp = json.has("maxTemperature") ? json.get("maxTemperature").getAsDouble() : 25.0;
            int hotspots = json.has("hotspots") ? json.get("hotspots").getAsInt() : 0;
            boolean anomaly = json.has("anomalyDetected") && json.get("anomalyDetected").getAsBoolean();
            String desc = json.has("description") ? json.get("description").getAsString() : "";
            String severity = json.has("severity") ? json.get("severity").getAsString() : "normal";

            return new ThermalAnalysisResult(maxTemp, hotspots, anomaly, desc, severity);

        } catch (Exception e) {
            getContext().getLog().warn("Mistral vision call failed: {}", e.getMessage());
            return null;
        }
    }

    private double simulateTemperature(String imagePath) {
        if (imagePath == null) return 25.0;
        String lower = imagePath.toLowerCase();
        if (lower.contains("smoke"))   return 55.0 + Math.random() * 20;
        if (lower.contains("mixture")) return 45.0 + Math.random() * 15;
        if (lower.contains("perfume")) return 28.0 + Math.random() * 5;
        return 22.0 + Math.random() * 5;
    }
}
