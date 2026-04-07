package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.*;
import org.jpmml.evaluator.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassificationAgent extends AbstractBehavior<ClassificationProtocol.ClassifyRequest> {

    private static final String[] SENSOR_ORDER = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};

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

        Evaluator eval = null;
        List<? extends InputField> fields = List.of();
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(pmmlPath);
            if (is == null) {
                is = new java.io.FileInputStream(pmmlPath);
            }
            eval = new LoadingModelEvaluatorBuilder()
                .load(is)
                .build();
            eval.verify();
            fields = eval.getInputFields();
            context.getLog().info("ClassificationAgent started — model loaded with {} input fields",
                fields.size());
        } catch (Exception e) {
            context.getLog().error("Failed to load PMML model from {}: {}", pmmlPath, e.getMessage());
            context.getLog().warn("ClassificationAgent running in FALLBACK mode — rule-based classification");
        }
        this.evaluator = eval;
        this.inputFields = fields;
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
        String hazardClass;
        double confidence;

        if (evaluator != null) {
            var result = classifyWithModel(fused);
            hazardClass = result.label;
            confidence = result.confidence;
        } else {
            var result = classifyWithRules(fused);
            hazardClass = result.label;
            confidence = result.confidence;
        }

        totalClassifications++;

        var classificationResult = new ClassificationProtocol.ClassificationResult(
            hazardClass, confidence, fused, Instant.now()
        );

        getContext().getLog().info("Classification #{}: {} (confidence={:.2f})",
            totalClassifications, hazardClass, confidence);

        request.replyTo().tell(classificationResult);

        return this;
    }

    private static final String[] CLASS_LABELS = {"Mixture", "NoGas", "Perfume", "Smoke"};

    private ClassResult classifyWithModel(FusionProtocol.FusedEvent fused) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();

            for (String sensor : SENSOR_ORDER) {
                SensorProtocol.SensorEvent event = fused.sensorEvents().get(sensor);
                double value = (event != null) ? event.valuePpm() : 0.0;
                input.put(sensor, value);
            }

            Map<String, ?> results = evaluator.evaluate(input);

            List<? extends TargetField> targetFields = evaluator.getTargetFields();
            String targetName = targetFields.get(0).getName();
            Object prediction = results.get(targetName);
            String rawLabel = prediction.toString();

            // Map numeric index to class name if needed
            String label = rawLabel;
            try {
                int idx = Integer.parseInt(rawLabel.replaceAll("[^0-9]", ""));
                if (idx >= 0 && idx < CLASS_LABELS.length) {
                    label = CLASS_LABELS[idx];
                }
            } catch (NumberFormatException e) {
                // Already a string label, use as-is
            }

            // Get max probability
            double prob = 0.85;
            List<? extends OutputField> outputFields = evaluator.getOutputFields();
            for (OutputField of : outputFields) {
                Object val = results.get(of.getName());
                if (val instanceof Number) {
                    double p = ((Number) val).doubleValue();
                    if (p > prob) prob = p;
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