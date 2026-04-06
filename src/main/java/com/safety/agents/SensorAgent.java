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

public class SensorAgent extends AbstractBehavior<SensorProtocol.SensorReading> {

    // Sharding entity key — each MQ sensor type is a separate shard
    public static final EntityTypeKey<SensorProtocol.SensorReading> ENTITY_KEY =
        EntityTypeKey.create(SensorProtocol.SensorReading.class, "SensorAgent");

    // Rolling window for stats
    private static final int WINDOW_SIZE = 30;
    private final Deque<Integer> window = new ArrayDeque<>();
    private final String sensorType;
    private final ActorRef<FusionProtocol.FusedEvent> fusionAgent;

    // Thresholds per sensor type (ppm values)
    private final int dangerThreshold;
    private int previousValue = 0;

    public static Behavior<SensorProtocol.SensorReading> create(
            String sensorType,
            ActorRef<FusionProtocol.FusedEvent> fusionAgent) {
        return Behaviors.setup(ctx -> new SensorAgent(ctx, sensorType, fusionAgent));
    }

    private SensorAgent(
            ActorContext<SensorProtocol.SensorReading> context,
            String sensorType,
            ActorRef<FusionProtocol.FusedEvent> fusionAgent) {
        super(context);
        this.sensorType = sensorType;
        this.fusionAgent = fusionAgent;
        this.dangerThreshold = getThreshold(sensorType);
        context.getLog().info("SensorAgent [{}] started, threshold: {} ppm", sensorType, dangerThreshold);
    }

    @Override
    public Receive<SensorProtocol.SensorReading> createReceive() {
        return newReceiveBuilder()
            .onMessage(SensorProtocol.SensorReading.class, this::onReading)
            .build();
    }

    private Behavior<SensorProtocol.SensorReading> onReading(SensorProtocol.SensorReading reading) {
        // Update rolling window
        window.addLast(reading.valuePpm());
        if (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }

        // Compute rolling stats
        double avg = window.stream().mapToInt(Integer::intValue).average().orElse(0);
        double stdDev = computeStdDev(avg);
        boolean breached = reading.valuePpm() > dangerThreshold;

        // Classify trend shape
        String trend = classifyTrend(reading.valuePpm());
        previousValue = reading.valuePpm();

        // Emit processed event
        var event = new SensorProtocol.SensorEvent(
            sensorType,
            reading.valuePpm(),
            avg,
            stdDev,
            breached,
            trend,
            reading.timestamp()
        );

        if (breached) {
            getContext().getLog().warn("[{}] THRESHOLD BREACHED: {} ppm (threshold: {})",
                sensorType, reading.valuePpm(), dangerThreshold);
        }

        // Send to Fusion Agent (in full implementation, Fusion collects from all shards)
        // For now, log it. Fusion Agent integration comes next.
        getContext().getLog().debug("[{}] value={} avg={} trend={}",
            sensorType, reading.valuePpm(), String.format("%.1f", avg), trend);

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
        int delta = currentValue - previousValue;
        double avgDelta = window.stream().mapToInt(Integer::intValue).average().orElse(0);
        
        if (Math.abs(delta) > dangerThreshold * 0.3) return "sudden";
        if (Math.abs(delta) > dangerThreshold * 0.05) return "gradual";
        return "stable";
    }

    private static int getThreshold(String sensorType) {
        return switch (sensorType) {
            case "MQ2"   -> 500;  // Flammable gases
            case "MQ3"   -> 400;  // Alcohol/ethanol
            case "MQ5"   -> 500;  // LPG/natural gas
            case "MQ6"   -> 500;  // LPG/butane
            case "MQ7"   -> 100;  // Carbon monoxide (most dangerous, lowest threshold)
            case "MQ8"   -> 500;  // Hydrogen
            case "MQ135" -> 400;  // Air quality
            default      -> 500;
        };
    }
}