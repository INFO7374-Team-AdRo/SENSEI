package edu.neu.safety.agents.sensor;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Event-sourced sharded sensor entity. One instance per sensor type (MQ2..MQ135).
 *
 * Active persistence: every IngestReading is persisted as a ReadingRecorded event
 * via Akka Persistence. State (rolling window, breach flag, trend) is rebuilt by
 * replay on restart, so a crashed/migrated shard recovers without losing history.
 *
 * Two failure modes are exposed for the demo: SimulateFailure throws an exception
 * (exercises SupervisorStrategy.restart), Stop terminates gracefully (exercises
 * cluster sharding's auto-restart-on-next-message).
 */
public class SensorAgent extends EventSourcedBehavior<SensorProtocol.Command,
        SensorProtocol.Event, SensorAgent.State> {

    private static final Logger log = LoggerFactory.getLogger(SensorAgent.class);

    private static final Map<String, Double> THRESHOLDS = Map.of(
        "MQ2",   500.0,
        "MQ3",   400.0,
        "MQ5",   500.0,
        "MQ6",   500.0,
        "MQ7",   100.0,
        "MQ8",   500.0,
        "MQ135", 400.0
    );

    private static final int WINDOW_SIZE = 30;

    private final String sensorType;

    /**
     * In-memory aggregate state for one sensor shard. Not immutable because
     * Akka Persistence replays events one by one through {@link #eventHandler()}
     * and we mutate in place — this is the pattern the Akka Typed docs use
     * for simple rolling-window state, and it's cheap enough for a 30-element
     * window.
     */
    public static class State {
        final String sensorType;
        final Deque<Double> readings = new ArrayDeque<>();
        double lastValue = 0;
        double rollingAvg = 0;
        double rollingMax = 0;
        int totalReadings = 0;
        boolean breached = false;
        String trend = "stable";

        public State(String sensorType) { this.sensorType = sensorType; }

        /**
         * Append a new reading and update the rolling stats. Also computes
         * a crude trend — comparing the average of the three most recent
         * readings against the average of the three before that — which is
         * good enough for the dashboard and doesn't need a proper time
         * series library.
         */
        void addReading(double value) {
            readings.addLast(value);
            if (readings.size() > WINDOW_SIZE) readings.removeFirst();
            lastValue = value;
            totalReadings++;

            double sum = 0;
            double max = Double.MIN_VALUE;
            for (double v : readings) {
                sum += v;
                max = Math.max(max, v);
            }
            rollingAvg = sum / readings.size();
            rollingMax = max;

            double threshold = THRESHOLDS.getOrDefault(sensorType, 500.0);
            breached = value > threshold;

            if (readings.size() >= 5) {
                Double[] arr = readings.toArray(new Double[0]);
                int len = arr.length;
                double recent = (arr[len - 1] + arr[len - 2] + arr[len - 3]) / 3.0;
                double older  = (arr[len - 3] + arr[len - 4] + arr[len - 5]) / 3.0;
                if (recent > older * 1.2)      trend = "rising";
                else if (recent < older * 0.8) trend = "falling";
                else                            trend = "stable";
            }
        }
    }

    /**
     * Factory wired up by Cluster Sharding. The supervisor restart strategy
     * limits restarts to 10 per minute so a persistently broken shard
     * eventually gives up rather than hot-spinning.
     *
     * @param sensorType sensor id used as both the persistence id suffix and the shard key
     */
    public static Behavior<SensorProtocol.Command> create(String sensorType) {
        return Behaviors.supervise(
            Behaviors.<SensorProtocol.Command>setup(context ->
                new SensorAgent(PersistenceId.of("SensorAgent", sensorType), sensorType))
        ).onFailure(SupervisorStrategy.restart().withLimit(10, Duration.ofMinutes(1)));
    }

    private SensorAgent(PersistenceId persistenceId, String sensorType) {
        super(persistenceId);
        this.sensorType = sensorType;
    }

    /** Initial state before any events are replayed. */
    @Override
    public State emptyState() { return new State(sensorType); }

    /** Command routing table — one handler per message type. */
    @Override
    public CommandHandler<SensorProtocol.Command, SensorProtocol.Event, State> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(SensorProtocol.IngestReading.class, this::onIngestReading)
            .onCommand(SensorProtocol.GetStatus.class,     this::onGetStatus)
            .onCommand(SensorProtocol.SimulateFailure.class, this::onSimulateFailure)
            .onCommand(SensorProtocol.Stop.class, (state, cmd) -> {
                log.warn("[{}] Stop received — graceful shutdown for fault-tolerance demo", sensorType);
                return Effect().stop();
            })
            .build();
    }

    /**
     * Persist the raw reading and then, after the journal ack, emit some
     * dashboard-friendly log lines (every threshold breach, plus a periodic
     * heartbeat every 50 readings to avoid log spam on clean runs).
     */
    private Effect<SensorProtocol.Event, State> onIngestReading(
            State state, SensorProtocol.IngestReading cmd) {
        SensorProtocol.ReadingRecorded event = new SensorProtocol.ReadingRecorded(
            cmd.sensorType(), cmd.value(), cmd.timestampMs()
        );
        return Effect().persist(event).thenRun(newState -> {
            if (newState.breached) {
                log.warn("[{}] THRESHOLD BREACH: value={} avg={} threshold={}",
                    sensorType, cmd.value(), String.format("%.2f", newState.rollingAvg),
                    THRESHOLDS.getOrDefault(sensorType, 500.0));
            }
            if (newState.totalReadings % 50 == 0) {
                log.info("[{}] {} readings persisted, last={}, avg={}, trend={}",
                    sensorType, newState.totalReadings, newState.lastValue,
                    String.format("%.2f", newState.rollingAvg), newState.trend);
            }
        });
    }

    /** Read-only query — returns a snapshot of the current aggregates. */
    private Effect<SensorProtocol.Event, State> onGetStatus(
            State state, SensorProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new SensorProtocol.SensorStatus(
            sensorType, state.lastValue, state.rollingAvg, state.rollingMax,
            state.totalReadings, state.breached, state.trend
        ));
        return Effect().none();
    }

    /**
     * Deliberately crash this actor so the supervisor has to restart it.
     * Used during the "Fault Tolerance Demo" on the dashboard — the next
     * message will arrive at the freshly replayed actor.
     */
    private Effect<SensorProtocol.Event, State> onSimulateFailure(
            State state, SensorProtocol.SimulateFailure cmd) {
        log.error("[{}] Simulated sensor failure! Throwing — supervisor will restart.", sensorType);
        throw new RuntimeException("Simulated failure for sensor: " + sensorType);
    }

    /** Replay handler — called for every persisted event both live and during recovery. */
    @Override
    public EventHandler<State, SensorProtocol.Event> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(SensorProtocol.ReadingRecorded.class, (state, event) -> {
                state.addReading(event.value());
                return state;
            })
            .build();
    }
}
