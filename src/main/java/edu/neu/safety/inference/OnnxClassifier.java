package edu.neu.safety.inference;

import ai.onnxruntime.*;
import edu.neu.safety.model.ClassificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Thin wrapper around ONNX Runtime that loads our XGBoost model exported to
 * ONNX and runs per-snapshot inference on the JVM.
 *
 * The classifier is invoked from the ClassificationAgent on each fused
 * snapshot. It is deliberately stateless between calls (apart from holding
 * the ONNX session) so that Akka can safely wrap it inside a
 * Behaviors.setup block and dispose of it by calling {@link #close()} when
 * the agent stops.
 *
 * Implements {@link AutoCloseable} so the agent can release the native
 * session and environment cleanly on shutdown — leaking ONNX sessions is a
 * known source of off-heap memory growth.
 */
public class OnnxClassifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxClassifier.class);
    private static final String[] LABELS = {"NO_GAS", "SMOKE", "PERFUME", "COMBINED"};

    private final OrtEnvironment env;
    private final OrtSession session;

    /**
     * Load the ONNX model from disk and create a session. Called once when
     * the ClassificationAgent starts.
     *
     * @param modelPath absolute or classpath-relative path to the .onnx file
     * @throws OrtException if the model is missing or the ONNX runtime can't parse it
     */
    public OnnxClassifier(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        this.session = env.createSession(modelPath, opts);
        log.info("ONNX model loaded: {} inputs, {} outputs",
            session.getInputNames().size(), session.getOutputNames().size());
    }

    /**
     * Run the model on a single feature vector and wrap the output as a
     * {@link ClassificationResult}.
     *
     * <p>XGBoost's ONNX exporter writes two output tensors — the first is the
     * predicted label index, the second is the softmax vector. We read the
     * second one because we want per-class confidence for the dashboard and
     * for downstream reasoning. Older exports only emit a single tensor, so
     * we fall back to that shape to stay compatible with the notebook if it
     * is re-run with different options.
     *
     * @param features length-7 MQ feature vector from {@link FeatureExtractor}
     * @return label + confidence + full probability vector
     */
    public ClassificationResult classify(float[] features) throws OrtException {
        OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][]{features});

        try (OrtSession.Result results = session.run(
                Map.of(session.getInputNames().iterator().next(), tensor))) {
            float[][] probabilities;
            if (results.size() > 1) {
                probabilities = (float[][]) results.get(1).getValue();
            } else {
                probabilities = (float[][]) results.get(0).getValue();
            }

            float[] probs = probabilities[0];
            int bestIdx = argmax(probs);
            String label = bestIdx < LABELS.length ? LABELS[bestIdx] : "UNKNOWN";

            return new ClassificationResult(label, probs[bestIdx], probs, System.currentTimeMillis());
        }
    }

    /** Standard argmax — returns the index of the largest element. */
    private int argmax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) best = i;
        }
        return best;
    }

    /** Release the native ONNX session so JVM shutdown doesn't leak memory. */
    @Override
    public void close() throws Exception {
        if (session != null) session.close();
    }
}
