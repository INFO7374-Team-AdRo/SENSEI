package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.safety.protocol.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public class SensorAgent extends AbstractBehavior<SensorAgent.Command> {

    // Sharding entity key
    public static final EntityTypeKey<Command> ENTITY_KEY =
        EntityTypeKey.create(Command.class, "SensorAgent");

    // Commands
    public interface Command {}

    public record ProcessReading(
        String sensorType, int valuePpm, Instant timestamp
    ) implements Command {}

    // Tells this shard where to send processed events
    public record SetFusionRef(ActorRef<FusionAgent.Command> fusionAgent) implements Command {}

    // Fault tolerance demo: gracefully stops this shard entity
    public record Stop() implements Command {}

    // Rolling window
    private static final int WINDOW_SIZE = 30;
    private final Deque<Integer> window = new ArrayDeque<>();
    private final String sensorType;
    private final int dangerThreshold;
    private int previousValue = 0;
    private ActorRef<FusionAgent.Command> fusionAgent;

    public static Behavior<Command> create(String sensorType) {
        return Behaviors.setup(ctx -> new SensorAgent(ctx, sensorType));
    }

    private SensorAgent(ActorContext<Command> context, String sensorType) {
        super(context);
        this.sensorType = sensorType;
        this.dangerThreshold = getThreshold(sensorType);
        context.getLog().info("SensorAgent [{}] started, threshold: {} ppm", sensorType, dangerThreshold);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ProcessReading.class, this::onReading)
            .onMessage(SetFusionRef.class, this::onSetFusion)
            .onMessage(Stop.class, msg -> {
                getContext().getLog().warn("[{}] Stop received — fault tolerance demo", sensorType);
                return Behaviors.stopped();
            })
            .build();
    }

    private Behavior<Command> onSetFusion(SetFusionRef msg) {
        this.fusionAgent = msg.fusionAgent();
        getContext().getLog().info("[{}] Fusion agent reference set", sensorType);
        return this;
    }

    private Behavior<Command> onReading(ProcessReading reading) {
        window.addLast(reading.valuePpm());
        if (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }

        double avg = window.stream().mapToInt(Integer::intValue).average().orElse(0);
        double stdDev = computeStdDev(avg);
        // ADC values DROP when gas is present (lower resistance = more gas).
        // Breach = value falls BELOW the calibrated low threshold for this sensor.
        boolean breached = reading.valuePpm() < dangerThreshold;
        String trend = classifyTrend(reading.valuePpm());
        previousValue = reading.valuePpm();

        var event = new SensorProtocol.SensorEvent(
            sensorType, reading.valuePpm(), avg, stdDev,
            breached, trend, reading.timestamp()
        );

        if (breached) {
            getContext().getLog().warn("[{}] THRESHOLD BREACHED: {} ppm (threshold: {})",
                sensorType, reading.valuePpm(), dangerThreshold);
        }

        // Forward to Fusion Agent
        if (fusionAgent != null) {
            fusionAgent.tell(new FusionAgent.SensorUpdate(event));
        }

        return this;
    }

    private double computeStdDev(double mean) {
        if (window.size() < 2) return 0;
        double sumSq = window.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum();
        return Math.sqrt(sumSq / window.size());
    }

    private String classifyTrend(int currentValue) {
        if (window.size() < 5) return "stable";
        // Negative delta = dropping ADC = rising gas concentration
        int delta = currentValue - previousValue;
        if (delta < -40) return "sudden";    // sharp drop → rapid gas rise
        if (delta < -10) return "gradual";   // slow drop → gradual gas rise
        if (delta > 40)  return "clearing";  // rising ADC → gas dispersing
        return "stable";
    }

    /**
     * Low-side ADC thresholds calibrated from Gas_Sensors_Measurements.csv.
     * These sensors output LOWER ADC when gas concentration rises.
     * NoGas baselines: MQ2≈748, MQ3≈529, MQ5≈431, MQ6≈425, MQ7≈606, MQ8≈637, MQ135≈474
     * Threshold = baseline minus ~15% safety margin (flags meaningful gas presence).
     */
    private static int getThreshold(String sensorType) {
        return switch (sensorType) {
            case "MQ2"   -> 690;  // NoGas≈748; Smoke drops to ~623, Mixture ~593
            case "MQ3"   -> 490;  // NoGas≈529; gas drops to ~372–419
            case "MQ5"   -> 400;  // NoGas≈431; gas drops to ~340–407
            case "MQ6"   -> 400;  // NoGas≈425; gas drops to ~368–376
            case "MQ7"   -> 560;  // NoGas≈606; Mixture drops to ~460
            case "MQ8"   -> 580;  // NoGas≈637; Mixture drops sharply to ~315
            case "MQ135" -> 430;  // NoGas≈474; Smoke drops to ~308
            default      -> 400;
        };
    }
}