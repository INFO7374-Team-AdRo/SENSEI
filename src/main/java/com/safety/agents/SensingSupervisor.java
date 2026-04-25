package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.MistralClient;
import com.safety.protocol.ClassificationProtocol;
import com.safety.protocol.EscalationProtocol;
import com.safety.protocol.LLMProtocol;

import java.time.Duration;

public class SensingSupervisor extends AbstractBehavior<SensingSupervisor.Command> {

    public interface Command {}

    public record Start(
        String pmmlPath,
        ActorRef<RetrievalAgent.Command> retrievalAgent,
        ActorRef<LLMProtocol.Command> llmAgent,
        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent,
        MistralClient mistral,
        String visionModel,
        ActorRef<Refs> replyTo
    ) implements Command {}

    public record Refs(
        ActorRef<FusionAgent.Command> fusionAgent,
        ActorRef<ThermalAgent.Command> thermalAgent,
        ActorRef<AudioAgent.Command> audioAgent
    ) {}

    public static Behavior<Command> create() {
        return Behaviors.setup(SensingSupervisor::new);
    }

    private SensingSupervisor(ActorContext<Command> context) {
        super(context);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Start.class, this::onStart)
            .build();
    }

    private Behavior<Command> onStart(Start msg) {
        ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent = getContext().spawn(
            Behaviors.supervise(ClassificationAgent.create(msg.pmmlPath(), msg.retrievalAgent()))
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "classification-agent"
        );

        ActorRef<FusionAgent.Command> fusionAgent = getContext().spawn(
            Behaviors.supervise(FusionAgent.create(
                classificationAgent, msg.retrievalAgent(), msg.llmAgent(), msg.escalationAgent()))
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "fusion-agent"
        );

        ActorRef<ThermalAgent.Command> thermalAgent = getContext().spawn(
            Behaviors.supervise(ThermalAgent.create(msg.mistral(), msg.visionModel()))
                .onFailure(Exception.class, SupervisorStrategy.restartWithBackoff(
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2)),
            "thermal-agent"
        );

        thermalAgent.tell(new ThermalAgent.SetFusionRef(fusionAgent));

        ActorRef<AudioAgent.Command> audioAgent = getContext().spawn(
            Behaviors.supervise(AudioAgent.create())
                .onFailure(Exception.class, SupervisorStrategy.restartWithBackoff(
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2)),
            "audio-agent"
        );
        audioAgent.tell(new AudioAgent.SetFusionRef(fusionAgent));

        getContext().getLog().info("SensingSupervisor: spawned classification + fusion + thermal + audio");

        msg.replyTo().tell(new Refs(fusionAgent, thermalAgent, audioAgent));

        return this;
    }
}
