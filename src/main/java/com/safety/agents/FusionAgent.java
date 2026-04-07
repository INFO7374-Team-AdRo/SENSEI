package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class FusionAgent extends AbstractBehavior<FusionAgent.Command> {

    public interface Command {}
    public record SensorUpdate(SensorProtocol.SensorEvent event) implements Command {}
    public record ThermalUpdate(ThermalProtocol.ThermalEvent event) implements Command {}
    private record FlushWindow() implements Command {}

    private static final Duration WINDOW_DURATION = Duration.ofSeconds(5);

    private final Map<String, SensorProtocol.SensorEvent> currentSensorEvents = new HashMap<>();
    private ThermalProtocol.ThermalEvent currentThermalEvent = null;
    private Instant windowStart = Instant.now();

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
            .onMessage(FlushWindow.class, this::onFlush)
            .onMessage(ClassificationResultReceived.class, this::onClassificationResult)
            .onMessage(RetrievalResultReceived.class, this::onRetrievalResult)
            .onMessage(ReasoningResultReceived.class, this::onReasoningResult)
            .build();
    }

    private Behavior<Command> onSensorUpdate(SensorUpdate msg) {
        currentSensorEvents.put(msg.event().sensorType(), msg.event());
        checkAndFuse();
        return this;
    }

    private Behavior<Command> onThermalUpdate(ThermalUpdate msg) {
        currentThermalEvent = msg.event();
        checkAndFuse();
        return this;
    }

    private Behavior<Command> onFlush(FlushWindow msg) {
        if (!currentSensorEvents.isEmpty()) emitFusedEvent();
        return this;
    }

    private void checkAndFuse() {
        boolean allSensors = currentSensorEvents.size() >= 7;
        boolean hasThermal = currentThermalEvent != null;
        boolean windowExpired = Duration.between(windowStart, Instant.now())
            .compareTo(WINDOW_DURATION) > 0;

        if ((allSensors && hasThermal) || windowExpired) {
            emitFusedEvent();
        }
    }

    private void emitFusedEvent() {
        if (currentSensorEvents.isEmpty()) return;

        double confidence = calculateConfidence();

        var fusedEvent = new FusionProtocol.FusedEvent(
            new HashMap<>(currentSensorEvents),
            currentThermalEvent,
            confidence,
            Instant.now()
        );

        getContext().getLog().info("FUSED EVENT: confidence={} sensors={} thermal={}",
            String.format("%.2f", confidence),
            currentSensorEvents.size(),
            currentThermalEvent != null);

        // Send to Classification Agent with reply adapter
        classificationAgent.tell(new ClassificationProtocol.ClassifyRequest(
            fusedEvent, classificationResultAdapter
        ));

        // Reset window
        currentSensorEvents.clear();
        currentThermalEvent = null;
        windowStart = Instant.now();
    }

    // Step 2: Classification result received → send to Retrieval
    private Behavior<Command> onClassificationResult(ClassificationResultReceived msg) {
        var result = msg.result();
        getContext().getLog().info("Classification: {} confidence={}",
            result.hazardClass(), String.format("%.2f", result.confidence()));

        // Only escalate if confidence > 0.5
        if (result.confidence() > 0.5 && !"NoGas".equals(result.hazardClass())) {
            // Create adapter for retrieval result
            ActorRef<RetrievalProtocol.RetrievalResult> retrievalAdapter =
                getContext().messageAdapter(
                    RetrievalProtocol.RetrievalResult.class,
                    rr -> new RetrievalResultReceived(rr)
                );

            retrievalAgent.tell(new RetrievalAgent.Retrieve(
                new RetrievalProtocol.RetrieveRequest(result, retrievalAdapter)
            ));
        } else {
            getContext().getLog().debug("Low confidence or NoGas — skipping escalation");
        }

        return this;
    }

    // Step 3: Retrieval result received → send to LLM
    private Behavior<Command> onRetrievalResult(RetrievalResultReceived msg) {
        var result = msg.result();
        getContext().getLog().info("Retrieved {} similar incidents",
            result.similarIncidents().size());

        // Create adapter for LLM reasoning result
        ActorRef<LLMProtocol.ReasoningResult> reasoningAdapter =
            getContext().messageAdapter(
                LLMProtocol.ReasoningResult.class,
                rr -> new ReasoningResultReceived(rr, result.classification())
            );

        llmAgent.tell(new LLMProtocol.ReasoningRequest(result, reasoningAdapter));

        return this;
    }

    // Step 4: Reasoning result received → send to Escalation
    private Behavior<Command> onReasoningResult(ReasoningResultReceived msg) {
        var reasoning = msg.result();
        var classification = msg.classification();

        getContext().getLog().info("LLM reasoning: {} severity={} steps={}",
            reasoning.hazardType(), reasoning.severity(), reasoning.reasoningSteps());

        // Send to Escalation Agent
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