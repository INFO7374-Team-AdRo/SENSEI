package com.safety.protocol;

import java.time.Instant;

public class SensorProtocol {

    public record SensorReading(
        String sensorType,
        int valuePpm,
        Instant timestamp
    ) implements CborSerializable {}

    public record SensorEvent(
        String sensorType,
        int valuePpm,
        double rollingAvg,
        double stdDev,
        boolean thresholdBreached,
        String trendShape,
        Instant timestamp
    ) implements CborSerializable {}
}
