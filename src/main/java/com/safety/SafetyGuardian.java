package com.safety;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import com.safety.agents.*;
import com.safety.clients.MistralClient;
import com.safety.clients.QdrantClient;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.*;
import com.safety.stream.DataReplayStream;
import com.typesafe.config.Config;

public class SafetyGuardian extends AbstractBehavior<SafetyGuardian.Command> {

    public interface Command {}
    public record StartSystem() implements Command {}
    private record IncidentReceived(OrchestratorProtocol.IncidentFinalized incident) implements Command {}

    private boolean started = false;

    public static Behavior<Command> create() {
        return Behaviors.setup(SafetyGuardian::new);
    }

    private SafetyGuardian(ActorContext<Command> context) {
        super(context);
        context.getLog().info("SafetyGuardian starting...");
        context.getSelf().tell(new StartSystem());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(StartSystem.class, this::onStartSystem)
            .onMessage(IncidentReceived.class, this::onIncidentReceived)
            .build();
    }

    private Behavior<Command> onIncidentReceived(IncidentReceived msg) {
        // Forward to orchestrator — handled silently
        return this;
    }

    private Behavior<Command> onStartSystem(StartSystem cmd) {
        if (started) return this; // prevent double initialization
        started = true;
        getContext().getLog().info("Initializing actor hierarchy...");

        Config config = getContext().getSystem().settings().config();
        Config safetyConfig = config.getConfig("safety");

        // --- Initialize external clients ---
        MistralClient mistral = new MistralClient(
            safetyConfig.getString("mistral.api-key"),
            safetyConfig.getString("mistral.base-url"),
            safetyConfig.getString("mistral.model"),
            safetyConfig.getString("mistral.embed-model")
        );

        QdrantClient qdrant = new QdrantClient(
            safetyConfig.getString("qdrant.url"),
            safetyConfig.getString("qdrant.api-key"),
            safetyConfig.getString("qdrant.collection")
        );

        // --- Spawn agents bottom-up (dependencies first) ---

        // 1. Retrieval Agent (needs Qdrant + Mistral for embedding)
        ActorRef<RetrievalAgent.Command> retrievalAgent =
            getContext().spawn(RetrievalAgent.create(qdrant, mistral), "retrieval-agent");

        // 2. Orchestrator Agent (needs Retrieval for write-behind)
        ActorRef<OrchestratorAgent.Command> orchestratorAgent =
            getContext().spawn(OrchestratorAgent.create(retrievalAgent), "orchestrator-agent");

        // 3. LLM Reasoning Agent (needs Mistral)
        ActorRef<LLMProtocol.Command> llmReasoningAgent =
            getContext().spawn(LLMReasoningAgent.create(mistral), "llm-reasoning-agent");

        // 4. Escalation Agent (needs Orchestrator)
        ActorRef<OrchestratorProtocol.IncidentFinalized> orchestratorIncidentRef =
            getContext().messageAdapter(
                OrchestratorProtocol.IncidentFinalized.class,
                inc -> new IncidentReceived(inc)
            );

        // Create a direct ref that Escalation sends to Orchestrator
        ActorRef<OrchestratorProtocol.IncidentFinalized> orchestratorDirectRef =
            getContext().messageAdapter(
                OrchestratorProtocol.IncidentFinalized.class,
                inc -> {
                    orchestratorAgent.tell(new OrchestratorAgent.IncidentMsg(inc));
                    return new IncidentReceived(inc);
                }
            );

        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent =
            getContext().spawn(EscalationAgent.create(orchestratorDirectRef), "escalation-agent");

        // 5. Classification Agent
        ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent =
            getContext().spawn(
                ClassificationAgent.create(
                    safetyConfig.getString("model.pmml-path"),
                    retrievalAgent
                ),
                "classification-agent"
            );

        // 6. Fusion Agent (wired to full downstream chain)
        ActorRef<FusionAgent.Command> fusionAgent =
            getContext().spawn(
                FusionAgent.create(classificationAgent, retrievalAgent,
                    llmReasoningAgent, escalationAgent),
                "fusion-agent"
            );

        // 7. Thermal Agent
        ActorRef<ThermalProtocol.ThermalFrame> thermalAgent =
            getContext().spawn(ThermalAgent.create(), "thermal-agent");

        // 8. Sensor Agent Sharding
        ClusterSharding sharding = ClusterSharding.get(getContext().getSystem());
        sharding.init(
            Entity.of(SensorAgent.ENTITY_KEY, entityContext ->
                SensorAgent.create(entityContext.getEntityId())
            )
        );

        // --- Start HTTP server ---
        SafetyHttpServer httpServer = new SafetyHttpServer(getContext().getSystem());
        httpServer.start(
            safetyConfig.getString("http.host"),
            safetyConfig.getInt("http.port")
        );

        // --- Start data replay ---
        String csvPath = safetyConfig.getString("data.csv-path");
        long replayInterval = safetyConfig.getLong("data.replay-interval-ms");

        DataReplayStream replay = new DataReplayStream(
            getContext().getSystem(), sharding, csvPath, replayInterval, fusionAgent
        );
        replay.startReplay();

        getContext().getLog().info("All agents initialized. Full pipeline wired. System ready.");
        return this;
    }

    // ---- Entry point ----
    public static void main(String[] args) {
        ActorSystem<Command> system = ActorSystem.create(
            SafetyGuardian.create(),
            "industrial-safety"
        );
        system.log().info("Industrial Safety Monitoring System started");
    }
}