package edu.neu.safety.cluster;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.typed.Cluster;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.typesafe.config.Config;
import edu.neu.safety.agents.classification.ClassificationAgent;
import edu.neu.safety.agents.classification.ClassificationProtocol;
import edu.neu.safety.agents.escalation.EscalationAgent;
import edu.neu.safety.agents.escalation.EscalationProtocol;
import edu.neu.safety.agents.fusion.FusionAgent;
import edu.neu.safety.agents.fusion.FusionProtocol;
import edu.neu.safety.agents.reasoning.LLMReasoningAgent;
import edu.neu.safety.agents.reasoning.ReasoningProtocol;
import edu.neu.safety.agents.retrieval.RetrievalAgent;
import edu.neu.safety.agents.retrieval.RetrievalProtocol;
import edu.neu.safety.agents.sensor.SensorAgent;
import edu.neu.safety.agents.sensor.SensorProtocol;
import edu.neu.safety.agents.thermal.ThermalAgent;
import edu.neu.safety.agents.thermal.ThermalProtocol;
import edu.neu.safety.agents.orchestrator.OrchestratorAgent;
import edu.neu.safety.agents.orchestrator.OrchestratorProtocol;
import edu.neu.safety.external.MongoDBService;
import edu.neu.safety.http.SafetyHttpServer;
import edu.neu.safety.model.CborSerializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Per-node bootstrap: inspects this node's roles, initializes the sensor sharding entity
 * (every node needs the proxy), then spawns role-specific agents and registers them with
 * the cluster Receptionist so the orchestrator can discover them by ServiceKey.
 */
public class ClusterSetup extends AbstractBehavior<ClusterSetup.Command> {

    private static final Logger log = LoggerFactory.getLogger(ClusterSetup.class);

    /** Marker for any message this actor accepts. */
    public interface Command extends CborSerializable {}

    /** One-shot kick used to confirm the setup actor finished booting. */
    public record Initialize() implements Command {}

    /**
     * EntityTypeKey for the SensorAgent sharding — every node has to call
     * {@code sharding.init} with this key, otherwise non-hosting nodes can't
     * forward messages to sensor shards and you get {@code EntityNotSharded}
     * errors at runtime.
     */
    public static final EntityTypeKey<SensorProtocol.Command> SENSOR_ENTITY_KEY =
        EntityTypeKey.create(SensorProtocol.Command.class, "SensorAgent");

    private final Config config;
    private final ClusterSharding sharding;

    /** Factory — called once per node from {@link edu.neu.safety.Main}. */
    public static Behavior<Command> create(Config config) {
        return Behaviors.setup(context -> new ClusterSetup(context, config));
    }

    /**
     * Read the node's cluster roles and bring up exactly the agents that
     * role assignment asks for. Sensor sharding is initialized unconditionally
     * so proxy forwarding works from any node.
     */
    private ClusterSetup(ActorContext<Command> context, Config config) {
        super(context);
        this.config = config;
        this.sharding = ClusterSharding.get(context.getSystem());

        Cluster cluster = Cluster.get(context.getSystem());
        Set<String> roles = cluster.selfMember().getRoles();
        log.info("Node starting — address={} roles={}", cluster.selfMember().address(), roles);

        // Sharding init must happen on every node so non-hosting nodes can route via proxy.
        initSensorSharding();

        initIngestionAgents(context, roles);
        initReasoningAgents(context, roles);
        initOrchestratorNode(context, roles);

        log.info("ClusterSetup complete (roles={})", roles);
    }

    /**
     * Register the sensor-agent sharding entity. The entity id is the sensor
     * type string (e.g. "MQ2"), so every MQ2 message in the cluster is
     * routed to exactly one shard — but that shard can live on any node.
     */
    private void initSensorSharding() {
        sharding.init(
            Entity.of(SENSOR_ENTITY_KEY, entityContext -> {
                String sensorType = entityContext.getEntityId();
                log.info("Hydrating SensorAgent shard: {}", sensorType);
                return SensorAgent.create(sensorType);
            })
        );
        log.info("Sensor sharding initialized for 7 entity types (MQ2..MQ135)");
    }

    /**
     * Spawn thermal, fusion, and classification agents if this node holds
     * the ingestion/fusion roles. Each agent is registered with the
     * Receptionist under its ServiceKey so the orchestrator (possibly on a
     * different node) can find it without hard-coded addresses.
     */
    private void initIngestionAgents(ActorContext<Command> context, Set<String> roles) {
        if (!roles.contains(ClusterRoles.INGESTION) && !roles.contains(ClusterRoles.FUSION)) {
            return;
        }
        log.info("Spawning ingestion + fusion + classification agents");

        ActorRef<ThermalProtocol.Command> thermal =
            context.spawn(ThermalAgent.create(), "thermal-agent");
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.THERMAL, thermal));

        ActorRef<FusionProtocol.Command> fusion =
            context.spawn(FusionAgent.create(), "fusion-agent");
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.FUSION, fusion));

        String modelPath = config.getString("safety.classifier.model-path");
        ActorRef<ClassificationProtocol.Command> classification =
            context.spawn(ClassificationAgent.create(modelPath), "classification-agent");
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.CLASSIFICATION, classification));
    }

    /**
     * Bring up the reasoning half of the cluster: retrieval (Qdrant-backed
     * RAG), the Mistral chat agent, and the rule-based escalation agent.
     * API keys and URLs come from application.conf — if a key is missing we
     * pass an empty string and let the downstream client fall back to its
     * degraded mode.
     */
    private void initReasoningAgents(ActorContext<Command> context, Set<String> roles) {
        if (!roles.contains(ClusterRoles.REASONING) && !roles.contains(ClusterRoles.ESCALATION)) {
            return;
        }
        log.info("Spawning retrieval + reasoning + escalation agents");

        String mistralKey = config.hasPath("safety.mistral.api-key")
            ? config.getString("safety.mistral.api-key") : "";
        String mistralUrl = config.getString("safety.mistral.base-url");
        String embedModel = config.getString("safety.mistral.embed-model");
        String chatModel = config.getString("safety.mistral.chat-model");

        String qdrantUrl = config.getString("safety.qdrant.url");
        String qdrantApiKey = config.hasPath("safety.qdrant.api-key")
            ? config.getString("safety.qdrant.api-key") : "";
        String qdrantCollection = config.getString("safety.qdrant.collection");
        int vectorSize = config.getInt("safety.qdrant.vector-size");

        ActorRef<RetrievalProtocol.Command> retrieval = context.spawn(
            RetrievalAgent.create(mistralKey, mistralUrl, embedModel,
                qdrantUrl, qdrantApiKey, qdrantCollection, vectorSize),
            "retrieval-agent"
        );
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.RETRIEVAL, retrieval));

        ActorRef<ReasoningProtocol.Command> reasoning = context.spawn(
            LLMReasoningAgent.create(mistralKey, mistralUrl, chatModel),
            "reasoning-agent"
        );
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.REASONING, reasoning));

        ActorRef<EscalationProtocol.Command> escalation =
            context.spawn(EscalationAgent.create(), "escalation-agent");
        context.getSystem().receptionist().tell(
            Receptionist.register(AgentServiceKeys.ESCALATION, escalation));
    }

    /**
     * Orchestrator-side initialization — spawns the pipeline coordinator and
     * hands it a reference to MongoDB for incident archival, then starts the
     * Akka HTTP server that hosts the dashboard + REST endpoints.
     */
    private void initOrchestratorNode(ActorContext<Command> context, Set<String> roles) {
        if (!roles.contains(ClusterRoles.ORCHESTRATOR) && !roles.contains(ClusterRoles.DASHBOARD)) {
            return;
        }
        log.info("Initializing orchestrator + HTTP server");

        String mongoUri = config.hasPath("safety.mongodb.uri")
            ? config.getString("safety.mongodb.uri") : "";
        String mongoDb = config.getString("safety.mongodb.database");
        MongoDBService mongoService = new MongoDBService(mongoUri, mongoDb);

        ActorRef<OrchestratorProtocol.Command> orchestrator =
            context.spawn(OrchestratorAgent.create(config, mongoService), "orchestrator-agent");

        int httpPort = config.getInt("safety.http.port");
        String httpHost = config.getString("safety.http.host");
        SafetyHttpServer.start(context.getSystem(), httpHost, httpPort, orchestrator, mongoService);

        log.info("Orchestrator node ready, HTTP on {}:{}", httpHost, httpPort);
    }

    /** Message dispatch table. Only responds to {@link Initialize} so far. */
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Initialize.class, this::onInitialize)
            .build();
    }

    /** Log that startup finished. Separate handler in case we add probes later. */
    private Behavior<Command> onInitialize(Initialize msg) {
        log.info("Cluster setup initialization complete");
        return this;
    }
}
