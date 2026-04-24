package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.*;
import com.safety.http.SafetyHttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class FusionAgent extends AbstractBehavior<FusionAgent.Command> {

    public interface Command {}
    public record SensorUpdate(SensorProtocol.SensorEvent event) implements Command {}
    public record ThermalUpdate(ThermalProtocol.ThermalEvent event) implements Command {}
    public record AudioUpdate(AudioProtocol.AudioEvent event) implements Command {}
    private record FlushWindow() implements Command {}

    private static final Duration WINDOW_DURATION = Duration.ofSeconds(5);

    private final Map<String, SensorProtocol.SensorEvent> currentSensorEvents = new HashMap<>();
    private ThermalProtocol.ThermalEvent currentThermalEvent = null;
    private AudioProtocol.AudioEvent currentAudioEvent = null;
    private Instant windowStart = Instant.now();

    // Rate-limit LLM escalation: at most one escalation every 90 seconds.
    // Classification still runs per-row (fast PMML), but LLM+Escalation are throttled.
    // 90s prevents excessive incident accumulation when replaying dense hazard data.
    private static final Duration ESCALATION_COOLDOWN = Duration.ofSeconds(90);
    private Instant lastEscalationTime = Instant.EPOCH;

    // Downstream — wraps ClassificationAgent to handle reply
    private final ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent;
    private final ActorRef<ClassificationProtocol.ClassificationResult> classificationResultAdapter;

    // Next hop after classification
    private final ActorRef<RetrievalAgent.Command> retrievalAgent;
    private final ActorRef<LLMProtocol.Command> llmAgent;
    private final ActorRef<EscalationProtocol.EscalationRequest> escalationAgent;

    public static Behavior<Command> create(
            ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent,
            ActorRef<RetrievalAgent.Command> retrievalAgent,
            ActorRef<LLMProtocol.Command> llmAgent,
            ActorRef<EscalationProtocol.EscalationRequest> escalationAgent) {
        return Behaviors.setup(ctx -> new FusionAgent(ctx, classificationAgent,
            retrievalAgent, llmAgent, escalationAgent));
    }

    private FusionAgent(ActorContext<Command> context,
                        ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent,
                        ActorRef<RetrievalAgent.Command> retrievalAgent,
                        ActorRef<LLMProtocol.Command> llmAgent,
                        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent) {
        super(context);
        this.classificationAgent = classificationAgent;
        this.retrievalAgent = retrievalAgent;
        this.llmAgent = llmAgent;
        this.escalationAgent = escalationAgent;

        // Create adapter to receive classification results back
        this.classificationResultAdapter = context.messageAdapter(
            ClassificationProtocol.ClassificationResult.class,
            result -> new ClassificationResultReceived(result)
        );

        context.getLog().info("FusionAgent started — window: {}s", WINDOW_DURATION.getSeconds());
    }

    // Internal message for classification result callback
    private record ClassificationResultReceived(
        ClassificationProtocol.ClassificationResult result
    ) implements Command {}

    // Internal message for retrieval result callback
    private record RetrievalResultReceived(
        RetrievalProtocol.RetrievalResult result
    ) implements Command {}

    // Internal message for LLM reasoning result callback
    private record ReasoningResultReceived(
        LLMProtocol.ReasoningResult result,
        ClassificationProtocol.ClassificationResult classification
    ) implements Command {}

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SensorUpdate.class, this::onSensorUpdate)
            .onMessage(ThermalUpdate.class, this::onThermalUpdate)
            .onMessage(AudioUpdate.class, this::onAudioUpdate)
            .onMessage(FlushWindow.class, this::onFlush)
            .onMessage(ClassificationResultReceived.class, this::onClassificationResult)
            .onMessage(RetrievalResultReceived.class, this::onRetrievalResult)
            .onMessage(ReasoningResultReceived.class, this::onReasoningResult)
            .build();
    }

    private Behavior<Command> onSensorUpdate(SensorUpdate msg) {
        // Last-write-wins: each CSV row sends all 7 sensors nearly simultaneously.
        // We flush as soon as all 7 arrive (checkAndFuse allSensors condition),
        // so values always reflect a single coherent CSV row — no cross-row mixing.
        currentSensorEvents.put(msg.event().sensorType(), msg.event());
        checkAndFuse();
        return this;
    }

    private Behavior<Command> onThermalUpdate(ThermalUpdate msg) {
        currentThermalEvent = msg.event();
        checkAndFuse();
        return this;
    }

    private Behavior<Command> onAudioUpdate(AudioUpdate msg) {
        currentAudioEvent = msg.event();
        // Audio anomaly adds urgency — trigger fusion immediately if sensors are present
        if (msg.event().anomalyDetected() && !currentSensorEvents.isEmpty()) {
            getContext().getLog().info("AudioAgent anomaly detected ({}) — early fusion trigger",
                msg.event().label());
            emitFusedEvent();
        }
        return this;
    }

    private Behavior<Command> onFlush(FlushWindow msg) {
        if (!currentSensorEvents.isEmpty()) emitFusedEvent();
        return this;
    }

    private void checkAndFuse() {
        boolean allSensors = currentSensorEvents.size() >= 7;
        boolean windowExpired = Duration.between(windowStart, Instant.now())
            .compareTo(WINDOW_DURATION) > 0;

        // Flush as soon as all 7 sensors arrive — each CSV row sends all 7 nearly
        // simultaneously, so this fires once per row with a clean single-class vector.
        // Thermal is included when available but NOT required to trigger flush.
        // windowExpired is a safety net for sparse/partial data.
        if (allSensors || windowExpired) {
            emitFusedEvent();
        }
    }

    private void emitFusedEvent() {
        if (currentSensorEvents.isEmpty()) return;

        double confidence = calculateConfidence();

        var fusedEvent = new FusionProtocol.FusedEvent(
            new HashMap<>(currentSensorEvents),
            currentThermalEvent,
            currentAudioEvent,
            confidence,
            Instant.now()
        );

        getContext().getLog().info("FUSED EVENT: confidence={} sensors={} thermal={} audio={}",
            String.format("%.2f", confidence),
            currentSensorEvents.size(),
            currentThermalEvent != null,
            currentAudioEvent != null ? currentAudioEvent.label() : "none");

        // Send to Classification Agent with reply adapter
        classificationAgent.tell(new ClassificationProtocol.ClassifyRequest(
            fusedEvent, classificationResultAdapter
        ));

        // Push to HTTP server for dashboard
        SafetyHttpServer http = SafetyHttpServer.getInstance();
        if (http != null) {
            Map<String, Object> eventMap = new HashMap<>();
            eventMap.put("confidence", confidence);
            eventMap.put("sensorCount", currentSensorEvents.size());
            eventMap.put("hasThermal", currentThermalEvent != null);
            eventMap.put("hasAudio", currentAudioEvent != null);
            if (currentAudioEvent != null) {
                eventMap.put("audioLabel", currentAudioEvent.label());
                eventMap.put("audioAnomaly", currentAudioEvent.anomalyDetected());
            }
            eventMap.put("timestamp", Instant.now().toString());
            for (var entry : currentSensorEvents.entrySet()) {
                eventMap.put(entry.getKey(), entry.getValue().valuePpm());
            }
            http.pushSensorEvent(eventMap);
        }

        // Reset window
        currentSensorEvents.clear();
        currentThermalEvent = null;
        currentAudioEvent = null;
        windowStart = Instant.now();
    }

    // Step 2: Classification result received → send directly to LLM (RAG now used only in chat)
    private Behavior<Command> onClassificationResult(ClassificationResultReceived msg) {
        var result = msg.result();
        getContext().getLog().info("Classification: {} confidence={}",
            result.hazardClass(), String.format("%.2f", result.confidence()));

        // Only escalate for real industrial hazards with sufficient confidence.
        // "NoGas" = clean air, "Perfume" = VOC not an industrial hazard — both are non-events.
        boolean isNonHazard = "NoGas".equals(result.hazardClass())
                           || "Perfume".equals(result.hazardClass());
        boolean cooldownElapsed = Duration.between(lastEscalationTime, Instant.now())
            .compareTo(ESCALATION_COOLDOWN) > 0;

        if (result.confidence() > 0.5 && !isNonHazard && cooldownElapsed) {
            lastEscalationTime = Instant.now();

            // Skip Qdrant retrieval in the incident pipeline — RAG is reserved for chat queries.
            // Pass an empty similar-incidents list straight to the LLM reasoning step.
            var emptyContext = new RetrievalProtocol.RetrievalResult(result, java.util.List.of());
            ActorRef<LLMProtocol.ReasoningResult> reasoningAdapter =
                getContext().messageAdapter(
                    LLMProtocol.ReasoningResult.class,
                    rr -> new ReasoningResultReceived(rr, result)
                );
            llmAgent.tell(new LLMProtocol.ReasoningRequest(emptyContext, reasoningAdapter));
        } else {
            getContext().getLog().debug("Low confidence or NoGas — skipping escalation");
        }

        return this;
    }

    // Step 3 (now unused): kept as dead handler so the receive builder compiles.
    // RetrievalResultReceived will never be sent since Step 2 no longer calls Qdrant.
    private Behavior<Command> onRetrievalResult(RetrievalResultReceived msg) {
        return this;
    }

    // Step 4: Reasoning result received → send to Escalation
    private Behavior<Command> onReasoningResult(ReasoningResultReceived msg) {
        var reasoning = msg.result();
        var classification = msg.classification();

        getContext().getLog().info("LLM reasoning: {} severity={} steps={}",
            reasoning.hazardType(), reasoning.severity(), reasoning.reasoningSteps());

        // Send to Escalation Agent — tier assignment happens there.
        // pushEscalationEvent is called by OrchestratorAgent AFTER tier is known,
        // so only T2_ALERT and T3_SHUTDOWN reach the dashboard alert feed.
        escalationAgent.tell(new EscalationProtocol.EscalationRequest(
            reasoning, classification
        ));

        return this;
    }

    private double calculateConfidence() {
        long breachedCount = currentSensorEvents.values().stream()
            .filter(SensorProtocol.SensorEvent::thresholdBreached)
            .count();

        boolean thermalAnomaly = currentThermalEvent != null
            && currentThermalEvent.anomalyDetected();

        double sensorConfidence = (double) breachedCount / currentSensorEvents.size();

        if (thermalAnomaly && breachedCount > 0) {
            return Math.min(0.95, sensorConfidence * 1.3 + 0.2);
        } else if (thermalAnomaly && breachedCount == 0) {
            return 0.4;
        } else if (!thermalAnomaly && breachedCount > 0) {
            return sensorConfidence * 0.7;
        } else {
            return sensorConfidence * 0.3;
        }
    }
}