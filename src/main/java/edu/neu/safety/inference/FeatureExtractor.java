package edu.neu.safety.inference;

import edu.neu.safety.model.FusedSnapshot;

/**
 * Tiny helper that converts a {@link FusedSnapshot} into the flat float array
 * that the ONNX model expects.
 *
 * We keep this as its own class (instead of inlining it in the classifier)
 * for two reasons: first, feature order is fragile — it has to match the
 * order used at training time — so having one source of truth avoids silent
 * mis-alignment bugs; second, when we eventually retrain with thermal
 * included, only this class changes and the rest of the pipeline can stay
 * exactly the same.
 */
public class FeatureExtractor {

    /**
     * Extract the 7-dimensional MQ feature vector used by the current
     * production model. Missing sensors default to 0.0 rather than throwing
     * because during fault-tolerance demos we deliberately kill shards and
     * still want the classifier to run on a degraded feature set.
     *
     * <p><b>Feature order is load-bearing</b> — it must stay MQ2, MQ3, MQ5,
     * MQ6, MQ7, MQ8, MQ135 to match the training pipeline in the notebook.
     *
     * @param snapshot fused sensor + thermal snapshot from the FusionAgent
     * @return length-7 float array of ppm readings
     */
    public float[] extract(FusedSnapshot snapshot) {
        return new float[]{
            snapshot.sensorValues().getOrDefault("MQ2", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ3", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ5", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ6", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ7", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ8", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ135", 0.0).floatValue()
        };
    }

    /**
     * 9-dimensional variant that appends max and average thermal temperatures.
     * Not wired into the current model (which was trained on MQ only), but
     * left in for a future retrain where we fuse thermal into the features.
     */
    public float[] extractWithThermal(FusedSnapshot snapshot) {
        return new float[]{
            snapshot.sensorValues().getOrDefault("MQ2", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ3", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ5", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ6", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ7", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ8", 0.0).floatValue(),
            snapshot.sensorValues().getOrDefault("MQ135", 0.0).floatValue(),
            (float) snapshot.maxTemp(),
            (float) snapshot.avgTemp()
        };
    }
}
