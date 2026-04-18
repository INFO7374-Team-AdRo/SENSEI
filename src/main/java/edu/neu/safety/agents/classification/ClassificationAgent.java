package edu.neu.safety.agents.classification;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import edu.neu.safety.inference.FeatureExtractor;
import edu.neu.safety.inference.OnnxClassifier;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.FusedSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Classifies a {@link FusedSnapshot} as one of {@code NO_GAS}, {@code SMOKE},
 * {@code PERFUME}, or {@code COMBINED}. Tries the ONNX XGBoost model first
 * and falls back to a rule-based classifier if the model is missing or
 * inference throws.
 *
 * <p>The rule-based fallback exists for two reasons: first, it lets the
 * demo run on any laptop even without the ONNX model on disk, and second,
 * if ONNX inference ever hits a weird runtime error we'd rather degrade
 * than block the pipeline.
 *
 * <p>Supervisor restarts are capped at 3/minute. If the model is fundamentally
 * broken we want to stop retrying instead of thrashing.
 */
public class ClassificationAgent extends AbstractBehavior<ClassificationProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(ClassificationAgent.class);

    private final OnnxClassifier classifier;
    private final FeatureExtractor featureExtractor;
    private int classificationsCount = 0;
    private String lastLabel = "NONE";
    private float lastConfidence = 0f;

    /**
     * Factory. If the ONNX model fails to load, the agent still starts —
     * it just operates in rule-based-only mode.
     */
    public static Behavior<ClassificationProtocol.Command> create(String modelPath) {
        return Behaviors.supervise(
            Behaviors.<ClassificationProtocol.Command>setup(context ->
                new ClassificationAgent(context, modelPath))
        ).onFailure(SupervisorStrategy.restart().withLimit(3, Duration.ofMinutes(1)));
    }

    private ClassificationAgent(ActorContext<ClassificationProtocol.Command> context, String modelPath) {
        super(context);
        this.featureExtractor = new FeatureExtractor();
        OnnxClassifier tempClassifier = null;
        try {
            tempClassifier = new OnnxClassifier(modelPath);
            log.info("ClassificationAgent: ONNX model loaded from {}", modelPath);
        } catch (Exception e) {
            log.warn("ClassificationAgent: ONNX model unavailable at {} — using rule-based fallback", modelPath);
        }
        this.classifier = tempClassifier;
    }

    /** Message dispatch. */
    @Override
    public Receive<ClassificationProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ClassificationProtocol.Classify.class, this::onClassify)
            .onMessage(ClassificationProtocol.GetStatus.class, this::onGetStatus)
            .build();
    }

    /**
     * Run ONNX inference on the snapshot, or fall back to
     * {@link #ruleBasedClassify(FusedSnapshot)} if the model isn't loaded
     * or throws. Replies with a {@link ClassificationResult} to the asker.
     */
    private Behavior<ClassificationProtocol.Command> onClassify(ClassificationProtocol.Classify cmd) {
        FusedSnapshot snapshot = cmd.snapshot();
        ClassificationResult result;
        if (classifier != null) {
            try {
                float[] features = featureExtractor.extract(snapshot);
                result = classifier.classify(features);
            } catch (Exception e) {
                log.error("ONNX classification failed, falling back to rules", e);
                result = ruleBasedClassify(snapshot);
            }
        } else {
            result = ruleBasedClassify(snapshot);
        }

        classificationsCount++;
        lastLabel = result.label();
        lastConfidence = result.confidence();
        cmd.replyTo().tell(result);

        if (!result.label().equals("NO_GAS")) {
            log.info("Classification #{}: {} (confidence={})",
                classificationsCount, result.label(), String.format("%.2f", result.confidence()));
        }
        return this;
    }

    /** Read-only probe used by the dashboard. */
    private Behavior<ClassificationProtocol.Command> onGetStatus(ClassificationProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new ClassificationProtocol.ClassifierStatus(
            classifier != null, classificationsCount, lastLabel, lastConfidence
        ));
        return this;
    }

    /**
     * Hand-written decision rules. These are thresholds I tuned on the
     * dataset — deliberately conservative so the fallback errs on the side
     * of NO_GAS rather than raising false alarms. Thermal anomaly nudges
     * confidence upward when it agrees with the gas classification, which
     * roughly mirrors what the trained model does as well.
     */
    private ClassificationResult ruleBasedClassify(FusedSnapshot snapshot) {
        double mq2 = snapshot.sensorValues().getOrDefault("MQ2", 0.0);
        double mq3 = snapshot.sensorValues().getOrDefault("MQ3", 0.0);
        double mq7 = snapshot.sensorValues().getOrDefault("MQ7", 0.0);
        double mq135 = snapshot.sensorValues().getOrDefault("MQ135", 0.0);
        boolean thermalAnomaly = snapshot.thermalAnomaly();

        // MQ2 (flammable) + MQ7 (CO) both lean smoke; MQ3 (alcohol) + MQ135 (air quality)
        // lean perfume / VOCs. If both sides light up we call it COMBINED.
        boolean smokeIndicators = mq2 > 500 || mq7 > 30;
        boolean perfumeIndicators = mq3 > 300 || mq135 > 200;

        String label;
        float confidence;
        if (smokeIndicators && perfumeIndicators) {
            label = "COMBINED";
            confidence = thermalAnomaly ? 0.92f : 0.78f;
        } else if (smokeIndicators) {
            label = "SMOKE";
            confidence = thermalAnomaly ? 0.88f : 0.72f;
        } else if (perfumeIndicators) {
            label = "PERFUME";
            confidence = 0.75f;
        } else {
            label = "NO_GAS";
            confidence = 0.95f;
        }
        if (thermalAnomaly && !label.equals("NO_GAS")) {
            confidence = Math.min(confidence + 0.08f, 0.99f);
        }

        // Build a probability vector that's at least consistent — winning
        // class gets `confidence`, the rest split the remainder evenly.
        float[] probabilities = new float[4];
        int labelIdx = switch (label) {
            case "NO_GAS" -> 0;
            case "SMOKE" -> 1;
            case "PERFUME" -> 2;
            case "COMBINED" -> 3;
            default -> 0;
        };
        probabilities[labelIdx] = confidence;
        float remaining = 1.0f - confidence;
        for (int i = 0; i < 4; i++) {
            if (i != labelIdx) probabilities[i] = remaining / 3.0f;
        }
        return new ClassificationResult(label, confidence, probabilities, System.currentTimeMillis());
    }
}
