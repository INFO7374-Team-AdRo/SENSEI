package edu.neu.safety.agents.thermal;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import edu.neu.safety.replay.ThermalImageLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thermal-side of the sensing pipeline. Takes either a path to a thermal
 * image frame or a "give me synthetic features" request and hands back a
 * {@link ThermalProtocol.ThermalResult} that the fusion agent can correlate
 * with MQ sensor readings.
 *
 * The agent is stateless across restarts — framesProcessed and anomalyCount
 * are purely for operator visibility, so we don't bother persisting them.
 * Supervisor restarts are used instead of event sourcing because losing a
 * few counters is fine; replaying every frame ever processed would be a
 * waste.
 */
public class ThermalAgent extends AbstractBehavior<ThermalProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(ThermalAgent.class);

    private int framesProcessed = 0;
    private double lastMaxTemp = 0;
    private double lastAvgTemp = 0;
    private int anomalyCount = 0;

    /** Factory with a vanilla restart strategy — enough for a stateless worker. */
    public static Behavior<ThermalProtocol.Command> create() {
        return Behaviors.supervise(Behaviors.setup(ThermalAgent::new))
            .onFailure(SupervisorStrategy.restart());
    }

    private ThermalAgent(ActorContext<ThermalProtocol.Command> context) {
        super(context);
        log.info("ThermalAgent started");
    }

    /** Message dispatch table. */
    @Override
    public Receive<ThermalProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ThermalProtocol.ProcessFrame.class, this::onProcessFrame)
            .onMessage(ThermalProtocol.ProcessSynthetic.class, this::onProcessSynthetic)
            .onMessage(ThermalProtocol.GetStatus.class, this::onGetStatus)
            .build();
    }

    /** Process a real image off disk. Delegates the pixel math to {@link ThermalImageLoader}. */
    private Behavior<ThermalProtocol.Command> onProcessFrame(ThermalProtocol.ProcessFrame cmd) {
        ThermalImageLoader.ThermalFeatures features =
            ThermalImageLoader.extractFeatures(cmd.imagePath());
        updateState(features);
        cmd.replyTo().tell(new ThermalProtocol.ThermalResult(
            features.maxTemp(), features.avgTemp(), features.anomaly(), cmd.timestampMs()
        ));
        return this;
    }

    /** Produce synthetic thermal features — used when no image is available. */
    private Behavior<ThermalProtocol.Command> onProcessSynthetic(ThermalProtocol.ProcessSynthetic cmd) {
        ThermalImageLoader.ThermalFeatures features = ThermalImageLoader.generateSyntheticFeatures();
        updateState(features);
        cmd.replyTo().tell(new ThermalProtocol.ThermalResult(
            features.maxTemp(), features.avgTemp(), features.anomaly(), cmd.timestampMs()
        ));
        return this;
    }

    /** Read-only status for the dashboard's "agents" panel. */
    private Behavior<ThermalProtocol.Command> onGetStatus(ThermalProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new ThermalProtocol.ThermalStatus(
            framesProcessed, lastMaxTemp, lastAvgTemp, anomalyCount
        ));
        return this;
    }

    /** Roll the internal counters forward and emit a heartbeat every 50 frames. */
    private void updateState(ThermalImageLoader.ThermalFeatures features) {
        framesProcessed++;
        lastMaxTemp = features.maxTemp();
        lastAvgTemp = features.avgTemp();
        if (features.anomaly()) anomalyCount++;
        if (framesProcessed % 50 == 0) {
            log.info("ThermalAgent: {} frames, anomalies={}, lastMaxTemp={}",
                framesProcessed, anomalyCount, String.format("%.1f", lastMaxTemp));
        }
    }
}
