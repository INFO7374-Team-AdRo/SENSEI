package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.ThermalProtocol;

public class ThermalAgent extends AbstractBehavior<ThermalProtocol.ThermalFrame> {

    // Simple threshold — in real system this would be ML-based
    private static final double TEMP_ANOMALY_THRESHOLD = 45.0;
    private double lastMaxTemp = 20.0;

    public static Behavior<ThermalProtocol.ThermalFrame> create() {
        return Behaviors.setup(ThermalAgent::new);
    }

    private ThermalAgent(ActorContext<ThermalProtocol.ThermalFrame> context) {
        super(context);
        context.getLog().info("ThermalAgent started");
    }

    @Override
    public Receive<ThermalProtocol.ThermalFrame> createReceive() {
        return newReceiveBuilder()
            .onMessage(ThermalProtocol.ThermalFrame.class, this::onFrame)
            .build();
    }

    private Behavior<ThermalProtocol.ThermalFrame> onFrame(ThermalProtocol.ThermalFrame frame) {
        // Simulate thermal analysis from image path
        // In production: load image, run temp extraction
        double simulatedTemp = simulateTemperature(frame.imagePath());
        boolean anomaly = simulatedTemp > TEMP_ANOMALY_THRESHOLD;

        if (anomaly) {
            getContext().getLog().warn("THERMAL ANOMALY: {:.1f}°C from {}",
                simulatedTemp, frame.imagePath());
        }

        lastMaxTemp = simulatedTemp;

        var event = new ThermalProtocol.ThermalEvent(
            anomaly,
            simulatedTemp,
            frame.timestamp()
        );

        // TODO: send event to Fusion Agent
        getContext().getLog().debug("Thermal: {:.1f}°C anomaly={}", simulatedTemp, anomaly);

        return this;
    }

    // Simulate temperature based on image filename pattern
    // Images named like "123_Smoke" would have higher temps
    private double simulateTemperature(String imagePath) {
        if (imagePath == null) return 25.0;
        String lower = imagePath.toLowerCase();
        if (lower.contains("smoke")) return 55.0 + Math.random() * 20;
        if (lower.contains("mixture")) return 45.0 + Math.random() * 15;
        if (lower.contains("perfume")) return 28.0 + Math.random() * 5;
        return 22.0 + Math.random() * 5; // NoGas baseline
    }
}
