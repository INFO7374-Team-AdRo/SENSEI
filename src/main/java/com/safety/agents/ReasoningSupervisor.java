package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.MistralClient;
import com.safety.clients.MongoDBService;
import com.safety.clients.QdrantClient;
import com.safety.protocol.LLMProtocol;

import java.time.Duration;

public class ReasoningSupervisor extends AbstractBehavior<ReasoningSupervisor.Command> {

    public interface Command {}

    public record Start(
        QdrantClient qdrant,
        MistralClient mistral,
        MongoDBService mongo,
        ActorRef<Refs> replyTo
    ) implements Command {}

    public record Refs(
        ActorRef<RetrievalAgent.Command> retrievalAgent,
        ActorRef<LLMProtocol.Command> llmAgent
    ) {}

    public static Behavior<Command> create() {
        return Behaviors.setup(ReasoningSupervisor::new);
    }

    private ReasoningSupervisor(ActorContext<Command> context) {
        super(context);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Start.class, this::onStart)
            .build();
    }

    private Behavior<Command> onStart(Start msg) {
        ActorRef<RetrievalAgent.Command> retrievalAgent = getContext().spawn(
            Behaviors.supervise(RetrievalAgent.create(msg.qdrant(), msg.mistral()))
                .onFailure(Exception.class, SupervisorStrategy.restartWithBackoff(
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2)),
            "retrieval-agent"
        );

        ActorRef<LLMProtocol.Command> llmAgent = getContext().spawn(
            Behaviors.supervise(LLMReasoningAgent.create(msg.mistral(), msg.mongo(), retrievalAgent))
                .onFailure(Exception.class, SupervisorStrategy.restartWithBackoff(
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2)),
            "llm-reasoning-agent"
        );

        getContext().getLog().info("ReasoningSupervisor: spawned retrieval + llm with restartWithBackoff");

        msg.replyTo().tell(new Refs(retrievalAgent, llmAgent));

        return this;
    }
}
