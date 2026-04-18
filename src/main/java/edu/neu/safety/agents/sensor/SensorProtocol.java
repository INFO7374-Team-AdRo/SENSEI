package edu.neu.safety.agents.sensor;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;

/**
 * Message contract for the {@link SensorAgent}. Kept as a sealed interface
 * so the compiler tells us if a new command sneaks in without a handler.
 */
public interface SensorProtocol {

    /** Parent type for anything the agent accepts. */
    sealed interface Command extends CborSerializable permits
        IngestReading, GetStatus, SimulateFailure, Stop {}

    /**
     * A single reading from the CSV replay / live feed. Persisted into the
     * journal as a {@link ReadingRecorded} event.
     */
    record IngestReading(
        String sensorType,
        double value,
        long timestampMs
    ) implements Command {}

    /** Ask-pattern query — the agent replies with a {@link SensorStatus}. */
    record GetStatus(ActorRef<SensorStatus> replyTo) implements Command {}

    /**
     * Crash on purpose (throws a RuntimeException). Handled by
     * {@code SupervisorStrategy.restart()} — used in the fault-tolerance demo.
     */
    record SimulateFailure(String sensorType) implements Command {}

    /**
     * Graceful stop. The sharded entity will be re-instantiated on the next
     * message to this entity id, replaying the journal on the way up.
     */
    record Stop() implements Command {}

    /** Parent type for persisted events — only one variant today. */
    sealed interface Event extends CborSerializable permits ReadingRecorded {}

    /** Event written to the journal for every accepted reading. */
    record ReadingRecorded(
        String sensorType,
        double value,
        long timestampMs
    ) implements Event {}

    /**
     * Reply payload for {@link GetStatus}. Carries everything the dashboard
     * shows for a single sensor: last value, rolling stats, trend, breach flag.
     */
    record SensorStatus(
        String sensorType,
        double lastValue,
        double rollingAvg,
        double rollingMax,
        int readingsCount,
        boolean breached,
        String trend
    ) implements CborSerializable {}
}
