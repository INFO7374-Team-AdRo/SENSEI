package edu.neu.safety.agents.classification;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.FusedSnapshot;

/**
 * Message contract for the {@link ClassificationAgent}. The agent has a
 * very narrow public surface: classify a snapshot or report its status.
 */
public interface ClassificationProtocol {

    /** Parent marker for every command. */
    sealed interface Command extends CborSerializable permits
        Classify, GetStatus {}

    /**
     * Classify one fused snapshot. The agent replies with a
     * {@link ClassificationResult} on the given replyTo.
     */
    record Classify(
        FusedSnapshot snapshot,
        ActorRef<ClassificationResult> replyTo
    ) implements Command {}

    /** Read-only status query for the dashboard's health panel. */
    record GetStatus(ActorRef<ClassifierStatus> replyTo) implements Command {}

    /**
     * Reply payload — includes whether the ONNX model actually loaded, so
     * the dashboard can distinguish "ML-powered" from "rule-based fallback"
     * at a glance.
     */
    record ClassifierStatus(
        boolean modelLoaded,
        int classificationsCount,
        String lastLabel,
        float lastConfidence
    ) implements CborSerializable {}
}
