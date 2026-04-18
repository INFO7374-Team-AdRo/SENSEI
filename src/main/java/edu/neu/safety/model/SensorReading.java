package edu.neu.safety.model;

import java.util.Map;

/**
 * One row of telemetry off the MQ sensor array. A reading is what each
 * SensorAgent shard emits after it processes a CSV row (or a live serial
 * packet, in the hardware version of this project).
 *
 * The label field is a holdover from the training dataset — in production
 * we would ignore it, but keeping it on the wire during the demo lets us
 * compare classifier output against ground truth on the dashboard.
 *
 * @param sensorValues per-sensor ppm readings keyed by sensor name
 * @param label        ground-truth label from the dataset, or null for live feeds
 * @param timestampMs  epoch millis when the reading was taken
 */
public record SensorReading(
    Map<String, Double> sensorValues,
    String label,
    long timestampMs
) implements CborSerializable {

    /**
     * Canonical order of the seven MQ sensors used across the project. Any
     * time we flatten sensor readings into a feature vector (e.g. for the
     * ONNX model) we must iterate in exactly this order so indices line up
     * with what the model was trained on.
     */
    public static final String[] SENSOR_TYPES = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};
}
