package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.EmailNotificationService;
import com.safety.clients.MongoDBService;
import com.safety.protocol.EscalationProtocol;
import com.safety.protocol.OrchestratorProtocol;

public class OutputSupervisor extends AbstractBehavior<OutputSupervisor.Command> {

    public interface Command {}

    public record Start(
        ActorRef<RetrievalAgent.Command> retrievalAgent,
        MongoDBService mongo,
        EmailNotificationService email,
        ActorRef<Refs> replyTo
    ) implements Command {}

    public record IncidentForward(
        OrchestratorProtocol.IncidentFinalized incident
    ) implements Command {}

    public record Refs(
        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent,
        ActorRef<OrchestratorAgent.Command> orchestratorAgent
    ) {}

    private ActorRef<OrchestratorAgent.Command> orchestratorRef;

    public static Behavior<Command> create() {
        return Behaviors.setup(OutputSupervisor::new);
    }

    private OutputSupervisor(ActorContext<Command> context) {
        super(context);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Start.class, this::onStart)
            .onMessage(IncidentForward.class, this::onIncidentForward)
            .build();
    }

    private Behavior<Command> onStart(Start msg) {
        ActorRef<OrchestratorAgent.Command> orchestrator = getContext().spawn(
            Behaviors.supervise(OrchestratorAgent.create(msg.retrievalAgent(), msg.mongo()))
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "orchestrator-agent"
        );
        this.orchestratorRef = orchestrator;

        ActorRef<OrchestratorProtocol.IncidentFinalized> adapter =
            getContext().messageAdapter(
                OrchestratorProtocol.IncidentFinalized.class,
                IncidentForward::new
            );

        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent = getContext().spawn(
            Behaviors.supervise(EscalationAgent.create(adapter, msg.email()))
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "escalation-agent"
        );

        getContext().getLog().info("OutputSupervisor: spawned escalation + orchestrator with restart");

        msg.replyTo().tell(new Refs(escalationAgent, orchestrator));

        return this;
    }

    private Behavior<Command> onIncidentForward(IncidentForward msg) {
        orchestratorRef.tell(new OrchestratorAgent.IncidentMsg(msg.incident()));
        return this;
    }
}
