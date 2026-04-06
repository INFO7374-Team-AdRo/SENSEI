package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class OrchestratorAgent extends AbstractBehavior<OrchestratorAgent.Command> {

    public interface Command {}

    // Wrap protocol messages
    public record IncidentMsg(OrchestratorProtocol.IncidentFinalized incident) implements Command {}
    public record StatusRequest(OrchestratorProtocol.GetSystemStatus request) implements Command {}

    // Internal state
    private final List<OrchestratorProtocol.IncidentFinalized> incidentLog = new ArrayList<>();
    private final List<EscalationProtocol.EscalationDecision> recentEscalations = new ArrayList<>();
    private int totalEventsProcessed = 0;

    // For storing incidents in Qdrant
    private final ActorRef<RetrievalAgent.Command> retrievalAgent;

    public static Behavior<Command> create(ActorRef<RetrievalAgent.Command> retrievalAgent) {
        return Behaviors.setup(ctx -> new OrchestratorAgent(ctx, retrievalAgent));
    }

    private OrchestratorAgent(ActorContext<Command> context,
                              ActorRef<RetrievalAgent.Command> retrievalAgent) {
        super(context);
        this.retrievalAgent = retrievalAgent;
        context.getLog().info("OrchestratorAgent started");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(IncidentMsg.class, this::onIncident)
            .onMessage(StatusRequest.class, this::onStatusRequest)
            .build();
    }

    private Behavior<Command> onIncident(IncidentMsg msg) {
        var incident = msg.incident();
        totalEventsProcessed++;
        incidentLog.add(incident);
        recentEscalations.add(incident.decision());

        // Keep only last 50 escalations in memory
        if (recentEscalations.size() > 50) {
            recentEscalations.remove(0);
        }

        getContext().getLog().info("Incident {} finalized — tier={} class={} cause={}",
            incident.incidentId(),
            incident.decision().tier(),
            incident.classification().hazardClass(),
            incident.reasoning().causalChain());

        // Store in Qdrant for future retrieval (write-behind)
        String summary = String.format("%s event — %s — %s",
            incident.classification().hazardClass(),
            incident.reasoning().causalChain(),
            incident.reasoning().recommendation());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("hazardClass", incident.classification().hazardClass());
        metadata.put("rootCause", incident.reasoning().causalChain());
        metadata.put("resolution", incident.reasoning().recommendation());
        metadata.put("severity", incident.reasoning().severity());
        metadata.put("tier", incident.decision().tier().name());

        retrievalAgent.tell(new RetrievalAgent.Store(
            new RetrievalProtocol.StoreIncident(incident.incidentId(), summary, metadata)
        ));

        // TODO: persist to MongoDB

        return this;
    }

    private Behavior<Command> onStatusRequest(StatusRequest msg) {
        var status = new OrchestratorProtocol.SystemStatus(
            7, // sensor shards (MQ2-MQ135)
            totalEventsProcessed,
            (int) incidentLog.stream()
                .filter(i -> i.decision().tier() != EscalationProtocol.Tier.T1_LOG)
                .count(),
            new ArrayList<>(recentEscalations)
        );

        msg.request().replyTo().tell(status);
        return this;
    }
}
