package edu.neu.safety.agents.thermal;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;

/**
 * Messages consumed and produced by the {@link ThermalAgent}. There are
 * two ways to ask for a thermal result (from a file path or synthetic)
 * plus a read-only status query used by the dashboard.
 */
public interface ThermalProtocol {

    /** Parent type for every inbound command the thermal agent handles. */
    sealed interface Command extends CborSerializable permits
        ProcessFrame, ProcessSynthetic, GetStatus {}

    /**
     * Process a real grayscale image off disk. The replyTo actor receives
     * a {@link ThermalResult} once pixel aggregation finishes.
     */
    record ProcessFrame(
        String imagePath,
        long timestampMs,
        ActorRef<ThermalResult> replyTo
    ) implements Command {}

    /** Ask for synthetic thermal features — used when no image is on disk. */
    record ProcessSynthetic(
        long timestampMs,
        ActorRef<ThermalResult> replyTo
    ) implements Command {}

    /** Fetch the operator-facing counters. */
    record GetStatus(ActorRef<ThermalStatus> replyTo) implements Command {}

    /**
     * Reply to a {@link ProcessFrame} / {@link ProcessSynthetic}: summary
     * stats from a single thermal frame.
     */
    record ThermalResult(
        double maxTemp,
        double avgTemp,
        boolean anomaly,
        long timestampMs
    ) implements CborSerializable {}

    /** Reply to {@link GetStatus}: counters for the dashboard. */
    record ThermalStatus(
        int framesProcessed,
        double lastMaxTemp,
        double lastAvgTemp,
        int anomalyCount
    ) implements CborSerializable {}
}
