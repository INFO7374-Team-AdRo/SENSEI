package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.*;
import org.jpmml.evaluator.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassificationAgent extends AbstractBehavior<ClassificationProtocol.ClassifyRequest> {

    // Sensor types in CSV column order (columns 1-7)
    private static final String[] SENSOR_ORDER   = {"MQ2",  "MQ3",  "MQ5",  "MQ6",  "MQ7",  "MQ8",  "MQ135"};
    // PMML model uses x1…x7 (trained via sklearn with unnamed columns)
    private static final String[] PMML_FEAT_KEYS = {"x1",   "x2",   "x3",   "x4",   "x5",   "x6",   "x7"};

    private final Evaluator evaluator;
    private final List<? extends InputField> inputFields;
    private final ActorRef<RetrievalAgent.Command> retrievalAgent;
    private int totalClassifications = 0;

    public static Behavior<ClassificationProtocol.ClassifyRequest> create(
            String pmmlPath,
            ActorRef<RetrievalAgent.Command> retrievalAgent) {
        return Behaviors.setup(ctx -> new ClassificationAgent(ctx, pmmlPath, retrievalAgent));
    }

    private ClassificationAgent(ActorContext<ClassificationProtocol.ClassifyRequest> context,
                                 String pmmlPath,
                                 ActorRef<RetrievalAgent.Command> retrievalAgent) {
        super(context);
        this.retrievalAgent = retrievalAgent;

        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(pmmlPath);
            if (is == null) {
                is = new java.io.FileInputStream(pmmlPath);
            }
            Evaluator eval = new LoadingModelEvaluatorBuilder()
                .load(is)
                .build();
            eval.verify();
            this.evaluator = eval;
            this.inputFields = eval.getInputFields();
            context.getLog().info("ClassificationAgent started — model loaded with {} input fields",
                this.inputFields.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load PMML model from " + pmmlPath, e);
        }
    }

    @Override
    public Receive<ClassificationProtocol.ClassifyRequest> createReceive() {
        return newReceiveBuilder()
            .onMessage(ClassificationProtocol.ClassifyRequest.class, this::onClassify)
            .build();
    }

    private Behavior<ClassificationProtocol.ClassifyRequest> onClassify(
            ClassificationProtocol.ClassifyRequest request) {

        FusionProtocol.FusedEvent fused = request.fusedEvent();

        var result = classifyWithModel(fused);
        String hazardClass = result.label;
        double confidence = result.confidence;

        totalClassifications++;

        // Push latest classification to the dashboard status endpoint
        SafetyHttpServer http = SafetyHttpServer.getInstance();
        if (http != null) http.updateLatestClassification(hazardClass);

        var classificationResult = new ClassificationProtocol.ClassificationResult(
            hazardClass, confidence, fused, Instant.now()
        );

        getContext().getLog().info("Classification #{}: {} (confidence={})",
            totalClassifications, hazardClass, String.format("%.2f", confidence));

        request.replyTo().tell(classificationResult);

        return this;
    }

    private static final String[] CLASS_LABELS = {"Mixture", "NoGas", "Perfume", "Smoke"};

    private ClassResult classifyWithModel(FusionProtocol.FusedEvent fused) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();

            for (int i = 0; i < SENSOR_ORDER.length; i++) {
                SensorProtocol.SensorEvent event = fused.sensorEvents().get(SENSOR_ORDER[i]);
                double value = (event != null) ? event.valuePpm() : 0.0;
                input.put(PMML_FEAT_KEYS[i], value);   // model expects x1…x7, not MQ2…MQ135
            }

            Map<String, ?> results = evaluator.evaluate(input);

            // Get the target field
            List<? extends TargetField> targetFields = evaluator.getTargetFields();
            String targetName = targetFields.get(0).getName();
            Object prediction = results.get(targetName);

            // Extract the actual label from the prediction object
            String label;
            double prob = 0.85;

            // JPMML wraps results — get the actual value
            String predStr = prediction.toString();
            getContext().getLog().debug("Raw PMML prediction: {}", predStr);

            // Try to extract the numeric result from ProbabilityDistribution or similar wrapper
            if (predStr.contains("result=")) {
                // Extract the number after "result="
                int start = predStr.indexOf("result=") + 7;
                int end = predStr.indexOf(",", start);
                if (end == -1) end = predStr.indexOf("}", start);
                String numStr = predStr.substring(start, end).trim();
                try {
                    int idx = Integer.parseInt(numStr);
                    label = (idx >= 0 && idx < CLASS_LABELS.length) ? CLASS_LABELS[idx] : "Unknown-" + idx;
                } catch (NumberFormatException e) {
                    label = numStr; // might already be a string label
                }
            } else {
                // Direct value — try as number first, then use as string
                try {
                    int idx = Integer.parseInt(predStr.trim());
                    label = (idx >= 0 && idx < CLASS_LABELS.length) ? CLASS_LABELS[idx] : "Unknown-" + idx;
                } catch (NumberFormatException e) {
                    label = predStr;
                }
            }

            // Extract max probability from the results
            if (predStr.contains("probability_entries=")) {
                int pStart = predStr.indexOf("probability_entries=[") + 21;
                int pEnd = predStr.indexOf("]", pStart);
                if (pStart > 20 && pEnd > pStart) {
                    String probSection = predStr.substring(pStart, pEnd);
                    for (String entry : probSection.split(",")) {
                        entry = entry.trim();
                        if (entry.contains("=")) {
                            String val = entry.split("=")[1].trim();
                            try {
                                double p = Double.parseDouble(val);
                                if (p > prob) prob = p;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            return new ClassResult(label, prob);

        } catch (Exception e) {
            getContext().getLog().warn("Model prediction failed: {} — using fallback", e.getMessage());
            return classifyWithRules(fused);
        }
    }

    private ClassResult classifyWithRules(FusionProtocol.FusedEvent fused) {
        if (fused.sensorEvents() == null || fused.sensorEvents().isEmpty()) {
            return new ClassResult("NoGas", 0.9);
        }

        int breachedCount = 0;
        boolean smokeSensors = false;
        boolean alcoholSensors = false;

        for (var entry : fused.sensorEvents().entrySet()) {
            if (entry.getValue().thresholdBreached()) {
                breachedCount++;
                switch (entry.getKey()) {
                    case "MQ2", "MQ135" -> smokeSensors = true;
                    case "MQ3" -> alcoholSensors = true;
                }
            }
        }

        if (breachedCount == 0) {
            return new ClassResult("NoGas", 0.85);
        }
        if (smokeSensors && alcoholSensors) {
            return new ClassResult("Mixture", 0.75);
        } else if (smokeSensors) {
            return new ClassResult("Smoke", 0.80);
        } else if (alcoholSensors) {
            return new ClassResult("Perfume", 0.70);
        } else {
            return new ClassResult("Smoke", 0.60);
        }
    }

    private record ClassResult(String label, double confidence) {}
}