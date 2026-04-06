package com.safety.protocol;

import java.time.Instant;

public class ThermalProtocol {

    public record ThermalFrame(
        String imagePath,
        Instant timestamp
    ) implements CborSerializable {}

    public record ThermalEvent(
        boolean anomalyDetected,
        double maxTemperature,
        Instant timestamp
    ) implements CborSerializable {}
}