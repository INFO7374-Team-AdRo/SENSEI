package edu.neu.safety.agents.escalation;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.EscalationTier;
import edu.neu.safety.agents.reasoning.ReasoningProtocol;

/**
 * Message contract for the {@link EscalationAgent}. The agent has a very
 * small public surface — one command to score an incident against the
 * escalation policy and one read-only status probe.
 */
public interface EscalationProtocol {

    /** Parent marker for inbound commands. */
    sealed interface Command extends CborSerializable permits
        Evaluate, GetStatus {}

    /**
     * Ask the escalation agent to pick a final tier for the current incident.
     * The reasoning agent's output carries the LLM's suggestion; the
     * classification carries the label + confidence used by the policy floor.
     */
    record Evaluate(
        ReasoningProtocol.ReasoningResult reasoning,
        ClassificationResult classification,
        ActorRef<EscalationDecision> replyTo
    ) implements Command {}

    /** Ask-pattern status probe used by the dashboard. */
    record GetStatus(ActorRef<EscalationStatus> replyTo) implements Command {}

    /**
     * Final escalation verdict — the tier, the operator-facing action string,
     * whether personnel need to be paged, and when the decision was stamped.
     */
    record EscalationDecision(
        EscalationTier tier,
        String action,
        boolean notifyPersonnel,
        long timestampMs
    ) implements CborSerializable {}

    /** Per-tier counters surfaced on the dashboard's escalation panel. */
    record EscalationStatus(
        int totalEvaluations,
        int t1Count,
        int t2Count,
        int t3Count,
        EscalationTier lastTier
    ) implements CborSerializable {}
}
