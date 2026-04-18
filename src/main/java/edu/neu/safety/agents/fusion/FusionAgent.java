package edu.neu.safety.agents.fusion;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import edu.neu.safety.model.FusedSnapshot;
import edu.neu.safety.model.SensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * Cross-modal correlator. Collects updates from the seven SensorAgent
 * shards plus the ThermalAgent, and emits a {@link FusedSnapshot} to all
 * subscribers once it has either
 *
 * <ul>
 *   <li>all seven sensor values plus a thermal value, or</li>
 *   <li>the 3-second window timer elapses — in which case it emits whatever
 *       it currently has, with missing sensors filled in as 0.0.</li>
 * </ul>
 *
 * <p>The "emit on timer" path is what keeps the pipeline alive during the
 * fault-tolerance demo: if we kill a sensor shard the orchestrator still
 * needs a snapshot every few seconds, even if it's temporarily missing a
 * sensor. The dashboard marks this state as "degraded".
 */
public class FusionAgent extends AbstractBehavior<FusionProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(FusionAgent.class);
    /** How long we wait for all modalities before we give up and emit anyway. */
    private static final Duration FUSION_WINDOW = Duration.ofSeconds(3);
    /** Number of MQ sensors that must report before a "complete" fusion fires. */
    private static final int REQUIRED_SENSORS = 7;

    private final List<ActorRef<FusionProtocol.FusionComplete>> subscribers = new ArrayList<>();

    private final Map<String, Double> currentSensorValues = new LinkedHashMap<>();
    private double currentMaxTemp = 0;
    private double currentAvgTemp = 0;
    private boolean thermalReceived = false;
    private long windowStartTime = 0;
    private String currentGroundTruth = null;

    private int fusionCount = 0;
    private long lastFusionTimestamp = 0;

    /** Factory — wraps the agent in a restart supervisor. */
    public static Behavior<FusionProtocol.Command> create() {
        return Behaviors.supervise(
            Behaviors.<FusionProtocol.Command>setup(context ->
                Behaviors.withTimers(timers -> new FusionAgent(context, timers)))
        ).onFailure(SupervisorStrategy.restart());
    }

    private FusionAgent(ActorContext<FusionProtocol.Command> context,
                        TimerScheduler<FusionProtocol.Command> timers) {
        super(context);
        // The periodic tick is what keeps the pipeline emitting even when a
        // sensor shard is dead — otherwise the dashboard would freeze during
        // the fault-injection demo.
        timers.startTimerWithFixedDelay("fusion-tick", new FusionProtocol.FusionTick(), FUSION_WINDOW);
        log.info("FusionAgent started ({}s window)", FUSION_WINDOW.getSeconds());
    }

    /** Message dispatch table. */
    @Override
    public Receive<FusionProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(FusionProtocol.SensorUpdate.class, this::onSensorUpdate)
            .onMessage(FusionProtocol.ThermalUpdate.class, this::onThermalUpdate)
            .onMessage(FusionProtocol.FusionTick.class, this::onFusionTick)
            .onMessage(FusionProtocol.GetStatus.class, this::onGetStatus)
            .onMessage(FusionProtocol.Subscribe.class, this::onSubscribe)
            .build();
    }

    /** Register a new downstream subscriber — typically the classification agent. */
    private Behavior<FusionProtocol.Command> onSubscribe(FusionProtocol.Subscribe cmd) {
        subscribers.add(cmd.subscriber());
        log.info("Subscriber added — total={}", subscribers.size());
        return this;
    }

    /**
     * Stash a sensor reading into the current window. If we now have all
     * seven modalities we can fire the fusion early instead of waiting for
     * the timer.
     */
    private Behavior<FusionProtocol.Command> onSensorUpdate(FusionProtocol.SensorUpdate cmd) {
        if (windowStartTime == 0) windowStartTime = cmd.timestampMs();
        currentSensorValues.put(cmd.sensorType(), cmd.value());
        if (cmd.groundTruthLabel() != null) currentGroundTruth = cmd.groundTruthLabel();
        if (currentSensorValues.size() >= REQUIRED_SENSORS && thermalReceived) emitFusion();
        return this;
    }

    /** Same idea but for the thermal side of the window. */
    private Behavior<FusionProtocol.Command> onThermalUpdate(FusionProtocol.ThermalUpdate cmd) {
        currentMaxTemp = cmd.maxTemp();
        currentAvgTemp = cmd.avgTemp();
        thermalReceived = true;
        if (currentSensorValues.size() >= REQUIRED_SENSORS) emitFusion();
        return this;
    }

    /**
     * Window timer — fires every {@link #FUSION_WINDOW}. Used to force a
     * snapshot out even when we don't have all seven sensors (useful both
     * for the dead-shard demo and for keeping the dashboard ticking during
     * very quiet periods).
     */
    private Behavior<FusionProtocol.Command> onFusionTick(FusionProtocol.FusionTick tick) {
        if (!currentSensorValues.isEmpty()) {
            if (currentSensorValues.size() < REQUIRED_SENSORS) {
                log.debug("FusionTick: forcing fusion with {}/{} sensors",
                    currentSensorValues.size(), REQUIRED_SENSORS);
            }
            emitFusion();
        }
        return this;
    }

    /** Read-only status for the dashboard. */
    private Behavior<FusionProtocol.Command> onGetStatus(FusionProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new FusionProtocol.FusionStatus(
            fusionCount, currentSensorValues.size(), thermalReceived, lastFusionTimestamp
        ));
        return this;
    }

    /**
     * Build a {@link FusedSnapshot} out of whatever we currently have and
     * broadcast it to every subscriber. Missing sensors are filled with 0.0
     * so the feature vector always has the same shape on the downstream
     * side. After emission we reset the window.
     */
    private void emitFusion() {
        Map<String, Double> completeValues = new LinkedHashMap<>();
        for (String sensor : SensorReading.SENSOR_TYPES) {
            completeValues.put(sensor, currentSensorValues.getOrDefault(sensor, 0.0));
        }
        boolean thermalAnomaly = currentMaxTemp > 50.0;
        boolean anySensorBreach = currentSensorValues.values().stream().anyMatch(v -> v > 500.0);
        // "Agreement" = both modalities think something's happening.
        // Useful heuristic to log, but not used downstream yet.
        boolean crossModalAgreement = anySensorBreach && thermalAnomaly;

        FusedSnapshot snapshot = new FusedSnapshot(
            completeValues,
            thermalReceived ? currentMaxTemp : 25.0,
            thermalReceived ? currentAvgTemp : 25.0,
            thermalAnomaly,
            System.currentTimeMillis(),
            currentGroundTruth
        );

        FusionProtocol.FusionComplete complete = new FusionProtocol.FusionComplete(snapshot);
        for (ActorRef<FusionProtocol.FusionComplete> sub : subscribers) sub.tell(complete);

        fusionCount++;
        lastFusionTimestamp = snapshot.timestampMs();
        if (fusionCount % 20 == 0) {
            log.info("Fusion #{}: sensors={}, thermal={}, crossModal={}",
                fusionCount, currentSensorValues.size(),
                thermalReceived ? "yes" : "no",
                crossModalAgreement ? "AGREE" : "diverge");
        }
        resetWindow();
    }

    /** Clear the fusion window so the next tick starts from scratch. */
    private void resetWindow() {
        currentSensorValues.clear();
        currentMaxTemp = 0;
        currentAvgTemp = 0;
        thermalReceived = false;
        windowStartTime = 0;
        currentGroundTruth = null;
    }
}
