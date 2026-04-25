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

    public static final EntityTypeKey<Command> ENTITY_KEY =
        EntityTypeKey.create(Command.class, "SensorAgent");

    public interface Command {}

    public record ProcessReading(
        String sensorType, int valuePpm, Instant timestamp
    ) implements Command {}

    public record SetFusionRef(ActorRef<FusionAgent.Command> fusionAgent) implements Command {}

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

    
    private static int getThreshold(String sensorType) {
        return switch (sensorType) {
            case "MQ3"   -> 490;  
            case "MQ5"   -> 400;  
            case "MQ6"   -> 400; 
            case "MQ7"   -> 560;  
            case "MQ8"   -> 580;  
            case "MQ135" -> 430;              
            default      -> 400;
        };
    }
}