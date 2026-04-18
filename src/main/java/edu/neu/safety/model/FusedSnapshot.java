package edu.neu.safety.model;

import java.util.Map;

/**
 * A single "moment in time" assembled by the FusionAgent. It bundles the
 * seven MQ-sensor readings together with the thermal camera stats so that
 * downstream agents (classification, reasoning) see a consistent view
 * instead of having to re-correlate streams themselves.
 *
 * The snapshot is what gets fed into the ONNX model, cached by the LLM
 * agent, and eventually persisted as part of the IncidentReport.
 *
 * @param sensorValues     ppm reading keyed by sensor name (MQ2..MQ135)
 * @param maxTemp          hottest pixel observed by the thermal camera (°C)
 * @param avgTemp          mean temperature across the frame (°C)
 * @param thermalAnomaly   true if the thermal frame crossed the anomaly threshold
 * @param timestampMs      epoch millis of the snapshot
 * @param groundTruthLabel label from the CSV replay, only populated in demo mode
 */
public record FusedSnapshot(
    Map<String, Double> sensorValues,
    double maxTemp,
    double avgTemp,
    boolean thermalAnomaly,
    long timestampMs,
    String groundTruthLabel
) implements CborSerializable {}
