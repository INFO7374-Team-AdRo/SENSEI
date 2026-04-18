package edu.neu.safety.agents.fusion;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.FusedSnapshot;

/**
 * Message contract for the {@link FusionAgent}. The agent takes per-modality
 * updates ({@link SensorUpdate}, {@link ThermalUpdate}) and emits
 * {@link FusionComplete} events to subscribers.
 */
public interface FusionProtocol {

    /** Parent marker for any command the fusion agent accepts. */
    sealed interface Command extends CborSerializable permits
        SensorUpdate, ThermalUpdate, FusionTick, GetStatus, Subscribe {}

    /** One sensor reading — forwarded by the orchestrator from the sensor shards. */
    record SensorUpdate(
        String sensorType,
        double value,
        long timestampMs,
        String groundTruthLabel
    ) implements Command {}

    /** One thermal frame summary from the ThermalAgent. */
    record ThermalUpdate(
        double maxTemp,
        double avgTemp,
        boolean anomaly,
        long timestampMs
    ) implements Command {}

    /** Internal timer — forces a fusion emission every N seconds. */
    record FusionTick() implements Command {}

    /** Read-only status query — used by /api-status. */
    record GetStatus(ActorRef<FusionStatus> replyTo) implements Command {}

    /**
     * Subscribe to completed fusions. Subscribers are held in the agent
     * state (in-memory list) and will receive every subsequent
     * {@link FusionComplete} event.
     */
    record Subscribe(ActorRef<FusionComplete> subscriber) implements Command {}

    /** Event broadcast when a snapshot has been assembled. */
    record FusionComplete(FusedSnapshot snapshot) implements CborSerializable {}

    /** Reply to {@link GetStatus}. */
    record FusionStatus(
        int fusionCount,
        int pendingSensors,
        boolean thermalReceived,
        long lastFusionTimestamp
    ) implements CborSerializable {}
}
