package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.*;

import java.time.Instant;

public class EscalationAgent extends AbstractBehavior<EscalationProtocol.EscalationRequest> {

    private final ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator;
    private int totalEscalations = 0;

    public static Behavior<EscalationProtocol.EscalationRequest> create(
            ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator) {
        return Behaviors.setup(ctx -> new EscalationAgent(ctx, orchestrator));
    }

    private EscalationAgent(ActorContext<EscalationProtocol.EscalationRequest> context,
                            ActorRef<OrchestratorProtocol.IncidentFinalized> orchestrator) {
        super(context);
        this.orchestrator = orchestrator;
        context.getLog().info("EscalationAgent started");
    }

    @Override
    public Receive<EscalationProtocol.EscalationRequest> createReceive() {
        return newReceiveBuilder()
            .onMessage(EscalationProtocol.EscalationRequest.class, this::onEscalation)
            .build();
    }

    private Behavior<EscalationProtocol.EscalationRequest> onEscalation(
            EscalationProtocol.EscalationRequest request) {

        double confidence = request.classification().confidence();
        String severity = request.reasoning().severity();

        // Determine tier
        EscalationProtocol.Tier tier;
        String action;

        if (confidence > 0.90 || "critical".equals(severity)) {
            tier = EscalationProtocol.Tier.T3_SHUTDOWN;
            action = "EMERGENCY: Triggering shutdown. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().error("🚨 T3 SHUTDOWN — {}", action);
        } else if (confidence > 0.75 || "high".equals(severity)) {
            tier = EscalationProtocol.Tier.T2_ALERT;
            action = "ALERT: Notifying operator. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().warn("⚠️ T2 ALERT — {}", action);
        } else {
            tier = EscalationProtocol.Tier.T1_LOG;
            action = "LOG: Recording event. Confidence=" + String.format("%.1f%%", confidence * 100);
            getContext().getLog().info("📝 T1 LOG — {}", action);
        }

        totalEscalations++;

        var decision = new EscalationProtocol.EscalationDecision(
            tier, action, request.reasoning(), Instant.now()
        );

        // Notify Orchestrator
        String incidentId = "INC-" + System.currentTimeMillis();
        orchestrator.tell(new OrchestratorProtocol.IncidentFinalized(
            incidentId,
            decision,
            request.classification(),
            request.reasoning()
        ));

        getContext().getLog().info("Escalation #{} — tier={} class={} confidence={:.2f}",
            totalEscalations, tier, request.classification().hazardClass(), confidence);

        return this;
    }
}
