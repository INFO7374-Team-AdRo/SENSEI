package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.EmailNotificationService;
import com.safety.protocol.*;

import java.time.Instant;

public class EscalationAgent extends AbstractBehavior<EscalationProtocol.EscalationRequest> {

    private final ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator;
    private final EmailNotificationService email;
    private int totalEscalations = 0;

    /** Without email notifications (backwards-compatible). */
    public static Behavior<EscalationProtocol.EscalationRequest> create(
            ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator) {
        return Behaviors.setup(ctx -> new EscalationAgent(ctx, orchestrator, null));
    }

    /** With email notifications wired in. */
    public static Behavior<EscalationProtocol.EscalationRequest> create(
            ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator,
            EmailNotificationService email) {
        return Behaviors.setup(ctx -> new EscalationAgent(ctx, orchestrator, email));
    }

    private EscalationAgent(ActorContext<EscalationProtocol.EscalationRequest> context,
                            ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator,
                            EmailNotificationService email) {
        super(context);
        this.orchestrator = orchestrator;
        this.email        = email;
        context.getLog().info("EscalationAgent started (email={})",
            email != null && email.isEnabled());
    }

    @Override
    public Receive<EscalationProtocol.EscalationRequest> createReceive() {
        return newReceiveBuilder()
            .onMessage(EscalationProtocol.EscalationRequest.class, this::onEscalation)
            .build();
    }

    private Behavior<EscalationProtocol.EscalationRequest> onEscalation(
            EscalationProtocol.EscalationRequest request) {

        // fusion confidence, not PMML probability (PMML sits at 0.9+ even for clear events)
        double confidence = (request.classification().fusedEvent() != null)
            ? request.classification().fusedEvent().fusionConfidence()
            : request.classification().confidence();
        String severity   = request.reasoning().severity();

        // >0.85 → T3 shutdown, >0.60 → T2 alert, else T1 log (LLM severity is informational only)
        EscalationProtocol.Tier tier;
        String action;

        if (confidence > 0.85) {
            tier   = EscalationProtocol.Tier.T3_SHUTDOWN;
            action = "EMERGENCY: Shutdown triggered. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().error("🚨 T3 SHUTDOWN — {}", action);
        } else if (confidence > 0.60) {
            tier   = EscalationProtocol.Tier.T2_ALERT;
            action = "ALERT: Operator notified. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().warn("⚠️ T2 ALERT — {}", action);
        } else {
            tier   = EscalationProtocol.Tier.T1_LOG;
            action = "LOG: Event recorded. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().info("📝 T1 LOG — {}", action);
        }

        totalEscalations++;

        Instant now = Instant.now();
        String incidentId = "INC-" + System.currentTimeMillis();

        // email on daemon thread so SMTP never blocks the actor mailbox
        if (email != null && email.isEnabled()
                && (tier == EscalationProtocol.Tier.T2_ALERT
                 || tier == EscalationProtocol.Tier.T3_SHUTDOWN)) {

            final String fTier      = tier.name();
            final String fHazard    = request.classification().hazardClass();
            final String fSeverity  = request.reasoning().severity();
            final double fConf      = confidence;
            final String fRec       = request.reasoning().recommendation();
            final String fCause     = request.reasoning().causalChain();
            final String fIncident  = incidentId;
            final Instant fTs       = now;

            Thread emailThread = new Thread(() ->
                email.sendEscalationAlert(
                    fTier, fHazard, fSeverity, fConf, fRec, fCause, fIncident, fTs));
            emailThread.setDaemon(true);
            emailThread.start();
        }

        var decision = new EscalationProtocol.EscalationDecision(
            tier, action, request.reasoning(), now
        );

        // Notify Orchestrator
        orchestrator.tell(new OrchestratorProtocol.IncidentFinalized(
            incidentId,
            decision,
            request.classification(),
            request.reasoning()
        ));

        getContext().getLog().info("Escalation #{} — tier={} class={} confidence={}",
            totalEscalations, tier, request.classification().hazardClass(),
            String.format("%.2f", confidence));

        return this;
    }
}
