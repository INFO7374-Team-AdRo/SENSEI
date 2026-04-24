package com.safety.protocol;

import java.time.Instant;

public class AudioProtocol {

    /** Incoming frame sent to AudioAgent for processing */
    public record AudioFrame(
        String filePath,        // absolute path to .wav file
        Instant timestamp
    ) implements CborSerializable {}

    /** Result published to FusionAgent */
    public record AudioEvent(
        boolean anomalyDetected,
        double rmsEnergy,       // normalized 0.0–1.0
        double dominantFreqHz,  // rough dominant frequency band
        String label,           // "Normal", "Mechanical", "Alarm", "Unknown"
        String description,
        Instant timestamp
    ) implements CborSerializable {}
}
