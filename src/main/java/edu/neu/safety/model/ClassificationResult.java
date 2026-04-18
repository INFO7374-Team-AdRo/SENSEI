package edu.neu.safety.model;

/**
 * Output of the ClassificationAgent for a single fused snapshot.
 *
 * The label is one of the four strings in {@link #LABELS} (same order as the
 * ONNX model's output head), confidence is the softmax value picked for that
 * label, and probabilities carries the full distribution so the dashboard can
 * render per-class bars. Timestamp is just the time the classification was
 * produced — useful when we later correlate events in MongoDB.
 *
 * @param label           winning class name
 * @param confidence      softmax probability for the winning class, in [0,1]
 * @param probabilities   full softmax vector, aligned with {@link #LABELS}
 * @param timestampMs     epoch millis when the classifier ran
 */
public record ClassificationResult(
    String label,
    float confidence,
    float[] probabilities,
    long timestampMs
) implements CborSerializable {

    /** Ordered label set — index i here corresponds to probabilities[i]. */
    public static final String[] LABELS = {"NO_GAS", "SMOKE", "PERFUME", "COMBINED"};
}
