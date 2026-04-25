package com.safety.protocol;

import java.time.Instant;
import java.util.Map;

public class FusionProtocol {

    public record FusedEvent(
        Map<String, SensorProtocol.SensorEvent> sensorEvents,
        ThermalProtocol.ThermalEvent thermalEvent,
        AudioProtocol.AudioEvent audioEvent,     // null when no audio available
        double fusionConfidence,
        Instant timestamp
    ) implements CborSerializable {}
}