package edu.neu.safety.agents.escalation;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import edu.neu.safety.model.EscalationTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safety-first tiered escalation policy.
 *
 * <p>The design idea here is that the classifier's confidence gives us a
 * trustworthy <em>floor</em>, and the LLM can only escalate above that floor —
 * never below. That way if the reasoning model has a bad day and downplays
 * something, the rule-based floor still kicks in; if it correctly spots a
 * worse situation than the classifier indicated, we go with its call.
 *
 * <p>Rules in short:
 * <ul>
 *   <li>Confidence drives the baseline tier (class-aware — see {@link #onEvaluate}).</li>
 *   <li>NO_GAS classifications never escalate.</li>
 *   <li>PERFUME caps at T1_LOG even at 100% confidence (benign VOC signal).</li>
 *   <li>LLM may escalate UP, never DOWN.</li>
 * </ul>
 */
public class EscalationAgent extends AbstractBehavior<EscalationProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(EscalationAgent.class);

    private int totalEvaluations = 0;
    private int t1Count = 0;
    private int t2Count = 0;
    private int t3Count = 0;
    private EscalationTier lastTier = EscalationTier.NONE;

    /**
     * Factory. Supervised with a plain restart because the agent is stateless
     * in the sense that counters don't need to survive a failure — the
     * dashboard will just see lower numbers after a crash.
     */
    public static Behavior<EscalationProtocol.Command> create() {
        return Behaviors.supervise(
            Behaviors.setup(EscalationAgent::new)
        ).onFailure(SupervisorStrategy.restart());
    }

    private EscalationAgent(ActorContext<EscalationProtocol.Command> context) {
        super(context);
        log.info("EscalationAgent started");
    }

    /** Dispatch table — evaluate a pending incident or return counters. */
    @Override
    public Receive<EscalationProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(EscalationProtocol.Evaluate.class, this::onEvaluate)
            .onMessage(EscalationProtocol.GetStatus.class, this::onGetStatus)
            .build();
    }

    /**
     * Score a candidate incident and reply with the final escalation decision.
     *
     * <p>Step 1 — compute the confidence-based floor, class-aware:
     * <ul>
     *   <li>NO_GAS → never escalates.</li>
     *   <li>PERFUME → capped at T1_LOG regardless of confidence; it's a VOC
     *       signal (cleaning solvents, air freshener, etc.) and the LLM is the
     *       judge of whether a particular reading actually matters.</li>
     *   <li>SMOKE / COMBINED → confidence thresholds 0.60/0.75/0.90 step up
     *       through T1 → T2 → T3.</li>
     * </ul>
     *
     * <p>Step 2 — take the max of the floor and the LLM's suggestion. This is
     * the "LLM up only" invariant: a weaker LLM verdict never lowers the tier.
     */
    private Behavior<EscalationProtocol.Command> onEvaluate(EscalationProtocol.Evaluate cmd) {
        float confidence = cmd.classification().confidence();
        EscalationTier llmSuggested = cmd.reasoning().suggestedTier();
        String label = cmd.classification().label();

        // Class-aware confidence floor. Only combustion-related classes can drive the
        // floor up to T3; PERFUME is a benign VOC signal and must not auto-shutdown on
        // high classifier confidence — the LLM is the decision-maker for its severity.
        EscalationTier confidenceTier;
        switch (label) {
            case "NO_GAS" -> confidenceTier = EscalationTier.NONE;
            case "PERFUME" -> confidenceTier = (confidence < 0.60f)
                ? EscalationTier.NONE : EscalationTier.T1_LOG;
            case "SMOKE", "COMBINED" -> {
                if (confidence < 0.60f)      confidenceTier = EscalationTier.NONE;
                else if (confidence < 0.75f) confidenceTier = EscalationTier.T1_LOG;
                else if (confidence < 0.90f) confidenceTier = EscalationTier.T2_ALERT;
                else                         confidenceTier = EscalationTier.T3_SHUTDOWN;
            }
            default -> confidenceTier = EscalationTier.NONE;
        }

        // Ordinal comparison on EscalationTier is meaningful because the enum
        // is declared in severity order — see EscalationTier.java.
        EscalationTier finalTier = confidenceTier.ordinal() >= llmSuggested.ordinal()
            ? confidenceTier : llmSuggested;

        String action = switch (finalTier) {
            case NONE -> "No action. Normal operations.";
            case T1_LOG -> "Event logged for review. Monitoring continues at standard rate.";
            case T2_ALERT -> "ALERT: Operator notified. Increased monitoring frequency. Safety team on standby.";
            case T3_SHUTDOWN -> "EMERGENCY: Simulated shutdown initiated. All personnel evacuation triggered. Emergency response deployed.";
        };

        boolean notifyPersonnel = finalTier.ordinal() >= EscalationTier.T2_ALERT.ordinal();

        totalEvaluations++;
        lastTier = finalTier;
        switch (finalTier) {
            case T1_LOG -> t1Count++;
            case T2_ALERT -> t2Count++;
            case T3_SHUTDOWN -> t3Count++;
            default -> {}
        }

        if (finalTier != EscalationTier.NONE) {
            log.warn("ESCALATION [{}]: {} | confidence={} | llm_suggested={} | action={}",
                finalTier, cmd.classification().label(), String.format("%.2f", confidence), llmSuggested, action);
        }

        cmd.replyTo().tell(new EscalationProtocol.EscalationDecision(
            finalTier, action, notifyPersonnel, System.currentTimeMillis()
        ));

        return this;
    }

    /** Dashboard probe — returns per-tier counters and the last tier reached. */
    private Behavior<EscalationProtocol.Command> onGetStatus(EscalationProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new EscalationProtocol.EscalationStatus(
            totalEvaluations, t1Count, t2Count, t3Count, lastTier
        ));
        return this;
    }
}
