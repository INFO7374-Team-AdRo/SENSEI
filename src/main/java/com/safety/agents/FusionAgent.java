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

    // Commands this agent accepts
    public interface Command {}

    public record SensorUpdate(SensorProtocol.SensorEvent event) implements Command {}
    public record ThermalUpdate(ThermalProtocol.ThermalEvent event) implements Command {}
    private record FlushWindow() implements Command {}

    // Time window for correlation
    private static final Duration WINDOW_DURATION = Duration.ofSeconds(5);

    // Current window state
    private final Map<String, SensorProtocol.SensorEvent> currentSensorEvents = new HashMap<>();
    private ThermalProtocol.ThermalEvent currentThermalEvent = null;
    private Instant windowStart = Instant.now();

    // Downstream
    private final ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent;

    public static Behavior<Command> create(
            ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent) {
        return Behaviors.setup(ctx -> new FusionAgent(ctx, classificationAgent));
    }

    private FusionAgent(ActorContext<Command> context,
                        ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent) {
        super(context);
        this.classificationAgent = classificationAgent;
        context.getLog().info("FusionAgent started — window: {}s", WINDOW_DURATION.getSeconds());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SensorUpdate.class, this::onSensorUpdate)
            .onMessage(ThermalUpdate.class, this::onThermalUpdate)
            .onMessage(FlushWindow.class, this::onFlush)
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
        if (!currentSensorEvents.isEmpty()) {
            emitFusedEvent();
        }
        return this;
    }

    private void checkAndFuse() {
        // Fuse when we have all 7 sensors + thermal, or window expires
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

        // Calculate fusion confidence
        double confidence = calculateConfidence();

        var fusedEvent = new FusionProtocol.FusedEvent(
            new HashMap<>(currentSensorEvents),
            currentThermalEvent,
            confidence,
            Instant.now()
        );

        getContext().getLog().info("FUSED EVENT: confidence={:.2f} sensors={} thermal={}",
            confidence, currentSensorEvents.size(), currentThermalEvent != null);

        // Send to Classification Agent
        if (classificationAgent != null) {
            // ClassifyRequest needs a replyTo — we'll handle this with ask pattern or adapter
            // For now, log it
            getContext().getLog().debug("Sending fused event to classification");
        }

        // Reset window
        currentSensorEvents.clear();
        currentThermalEvent = null;
        windowStart = Instant.now();
    }

    private double calculateConfidence() {
        // Count how many sensors breached their threshold
        long breachedCount = currentSensorEvents.values().stream()
            .filter(SensorProtocol.SensorEvent::thresholdBreached)
            .count();

        boolean thermalAnomaly = currentThermalEvent != null
            && currentThermalEvent.anomalyDetected();

        // Confidence formula:
        // - Base: proportion of sensors that breached
        // - Boost if thermal confirms
        // - Penalty if sensors disagree with thermal
        double sensorConfidence = (double) breachedCount / currentSensorEvents.size();

        if (thermalAnomaly && breachedCount > 0) {
            // Both modalities agree — high confidence
            return Math.min(0.95, sensorConfidence * 1.3 + 0.2);
        } else if (thermalAnomaly && breachedCount == 0) {
            // Thermal sees something, sensors don't — moderate uncertainty
            return 0.4;
        } else if (!thermalAnomaly && breachedCount > 0) {
            // Sensors see something, thermal doesn't — moderate
            return sensorConfidence * 0.7;
        } else {
            // Neither sees anything — low confidence (probably safe)
            return sensorConfidence * 0.3;
        }
    }
}
