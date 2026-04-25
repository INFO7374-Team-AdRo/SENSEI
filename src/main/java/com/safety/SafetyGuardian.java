package com.safety;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import com.safety.agents.*;
import com.safety.agents.VisualInspectionAgent;
import com.safety.clients.EmailNotificationService;
import com.safety.clients.MistralClient;
import com.safety.clients.MongoDBService;
import com.safety.clients.QdrantClient;
import com.safety.http.SafetyHttpServer;
import com.safety.agents.InspectionReceiverActor;
import com.safety.protocol.*;
import com.safety.protocol.InspectionProtocol;
import com.safety.stream.DataReplayStream;
import com.typesafe.config.Config;

import java.util.Set;

public class SafetyGuardian extends AbstractBehavior<SafetyGuardian.Command> {

    // ---- Service keys for Receptionist ----
    public static final ServiceKey<RetrievalAgent.Command> RETRIEVAL_KEY =
        ServiceKey.create(RetrievalAgent.Command.class, "retrieval-agent");
    public static final ServiceKey<LLMProtocol.Command> LLM_KEY =
        ServiceKey.create(LLMProtocol.Command.class, "llm-reasoning-agent");
    public static final ServiceKey<EscalationProtocol.EscalationRequest> ESCALATION_KEY =
        ServiceKey.create(EscalationProtocol.EscalationRequest.class, "escalation-agent");
    public static final ServiceKey<OrchestratorAgent.Command> ORCHESTRATOR_KEY =
        ServiceKey.create(OrchestratorAgent.Command.class, "orchestrator-agent");
    /** node-a registers this; node-d discovers it to deliver InspectionEvents cross-cluster. */
    public static final ServiceKey<InspectionProtocol.ReceiverCommand> INSPECTION_RECEIVER_KEY =
        ServiceKey.create(InspectionProtocol.ReceiverCommand.class, "inspection-receiver");

    // ---- Commands ----
    public interface Command {}
    public record StartSystem() implements Command {}
    private record IncidentReceived(OrchestratorProtocol.IncidentFinalized incident) implements Command {}

    // Phase messages for sequential supervisor startup
    private record GotReasoningRefs(ReasoningSupervisor.Refs refs) implements Command {}
    private record GotOutputRefs(OutputSupervisor.Refs refs) implements Command {}
    private record GotSensingRefs(SensingSupervisor.Refs refs) implements Command {}

    // Single wrapper for all Receptionist listings — Akka only supports one adapter per type
    private record AnyListing(Receptionist.Listing listing) implements Command {}
    private record InspectionReceiverListing(Receptionist.Listing listing) implements Command {}

    // ---- State ----
    private boolean started = false;

    
    private ActorRef<FusionAgent.Command> fusionAgentRef;
    private ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgentRef;

    private ActorRef<RetrievalAgent.Command> remoteRetrievalAgent;
    private ActorRef<LLMProtocol.Command> remoteLlmAgent;
    private ActorRef<EscalationProtocol.EscalationRequest> remoteEscalationAgent;
    private ActorRef<OrchestratorAgent.Command> remoteOrchestratorAgent;

    private ActorRef<EscalationProtocol.EscalationRequest> localEscalationAgent;
    private ActorRef<OrchestratorProtocol.IncidentFinalized> orchestratorIncidentAdapter;

    private ActorRef<VisualInspectionAgent.Command> localVisualInspectionAgent;

    // For HTTP server
    private SafetyHttpServer httpServer;

    // Hold partial state between all-in-one startup phases
    private Config pendingSafetyConfig;
    private MongoDBService pendingMongo;
    private MistralClient pendingMistral;
    private ReasoningSupervisor.Refs reasoningRefs;
    private OutputSupervisor.Refs outputRefs;

    // ---- Factory ----
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
            .onMessage(GotReasoningRefs.class, this::onGotReasoningRefs)
            .onMessage(GotOutputRefs.class, this::onGotOutputRefs)
            .onMessage(GotSensingRefs.class, this::onGotSensingRefs)
            .onMessage(AnyListing.class, this::onAnyListing)
            .onMessage(InspectionReceiverListing.class, this::onInspectionReceiverListing)
            .build();
    }

    private Behavior<Command> onIncidentReceived(IncidentReceived msg) {
        return this;
    }


    private Behavior<Command> onStartSystem(StartSystem cmd) {
        if (started) return this;
        started = true;

        Config config = getContext().getSystem().settings().config();
        Config safetyConfig = config.getConfig("safety");
        String role = safetyConfig.getString("node-role");

        getContext().getLog().info("SafetyGuardian initializing with role={}", role);

        switch (role) {
            case "node-a" -> startNodeA(safetyConfig);
            case "node-b" -> startNodeB(safetyConfig);
            case "node-c" -> startNodeC(safetyConfig);
            case "node-d" -> startNodeD(safetyConfig);
            default       -> startAllInOne(safetyConfig); // "all" or any unrecognised
        }

        return this;
    }


    private void startAllInOne(Config safetyConfig) {
        getContext().getLog().info("Starting in ALL-IN-ONE mode (single node)");

        // Save config for later phases
        this.pendingSafetyConfig = safetyConfig;
        this.pendingMistral = buildMistral(safetyConfig);
        this.pendingMongo = buildMongo(safetyConfig);
        QdrantClient qdrant = buildQdrant(safetyConfig);

        // Phase 1: spawn ReasoningSupervisor (no deps)
        ActorRef<ReasoningSupervisor.Refs> reasoningReplyTo =
            getContext().messageAdapter(ReasoningSupervisor.Refs.class, GotReasoningRefs::new);

        ActorRef<ReasoningSupervisor.Command> reasoningSupervisor = getContext().spawn(
            Behaviors.supervise(ReasoningSupervisor.create())
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "reasoning-supervisor"
        );
        reasoningSupervisor.tell(new ReasoningSupervisor.Start(qdrant, pendingMistral, pendingMongo, reasoningReplyTo));

        getContext().getLog().info("Phase 1: ReasoningSupervisor spawned, awaiting refs...");
    }

    private Behavior<Command> onGotReasoningRefs(GotReasoningRefs msg) {
        // Phase 2: spawn OutputSupervisor (needs retrievalAgent + mongo)
        this.reasoningRefs = msg.refs();
        ActorRef<OutputSupervisor.Refs> outputReplyTo =
            getContext().messageAdapter(OutputSupervisor.Refs.class, GotOutputRefs::new);

        ActorRef<OutputSupervisor.Command> outputSupervisor = getContext().spawn(
            Behaviors.supervise(OutputSupervisor.create())
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "output-supervisor"
        );
        EmailNotificationService emailSvc = buildEmail(pendingSafetyConfig);
        outputSupervisor.tell(new OutputSupervisor.Start(
            reasoningRefs.retrievalAgent(), pendingMongo, emailSvc, outputReplyTo));

        getContext().getLog().info("Phase 2: OutputSupervisor spawned, awaiting refs...");
        return this;
    }

    private Behavior<Command> onGotOutputRefs(GotOutputRefs msg) {
        // Phase 3: spawn SensingSupervisor (needs retrieval + llm + escalation + mistral)
        this.outputRefs = msg.refs();
        ActorRef<SensingSupervisor.Refs> sensingReplyTo =
            getContext().messageAdapter(SensingSupervisor.Refs.class, GotSensingRefs::new);

        ActorRef<SensingSupervisor.Command> sensingSupervisor = getContext().spawn(
            Behaviors.supervise(SensingSupervisor.create())
                .onFailure(Exception.class, SupervisorStrategy.restart()),
            "sensing-supervisor"
        );
        sensingSupervisor.tell(new SensingSupervisor.Start(
            pendingSafetyConfig.getString("model.pmml-path"),
            reasoningRefs.retrievalAgent(),
            reasoningRefs.llmAgent(),
            outputRefs.escalationAgent(),
            pendingMistral,
            pendingSafetyConfig.getString("mistral.vision-model"),
            sensingReplyTo
        ));

        getContext().getLog().info("Phase 3: SensingSupervisor spawned, awaiting refs...");
        return this;
    }

    private Behavior<Command> onGotSensingRefs(GotSensingRefs msg) {
        // Phase 4: wire HTTP + DataReplayStream with all refs
        ClusterSharding sharding = ClusterSharding.get(getContext().getSystem());
        sharding.init(Entity.of(SensorAgent.ENTITY_KEY,
            entityContext -> SensorAgent.create(entityContext.getEntityId())));

        SafetyHttpServer http = new SafetyHttpServer(getContext().getSystem());
        http.setLlmAgent(reasoningRefs.llmAgent());
        http.setOrchestratorAgent(outputRefs.orchestratorAgent());
        http.setThermalAgent(msg.refs().thermalAgent());
        http.setSharding(sharding);
        http.setMongo(pendingMongo);

        // InspecSafe dataset root (defaults to ./inspecsafe-v1 if not configured)
        String inspecsafeRoot = pendingSafetyConfig.hasPath("inspecsafe.data-path")
            ? pendingSafetyConfig.getString("inspecsafe.data-path")
            : "./inspecsafe-v1";
        long inspecsafeInterval = pendingSafetyConfig.hasPath("inspecsafe.replay-interval-ms")
            ? pendingSafetyConfig.getLong("inspecsafe.replay-interval-ms")
            : 20000L;
        http.setInspecsafeDataRoot(inspecsafeRoot);
        http.setMistral(pendingMistral);
        http.setRetrievalAgent(reasoningRefs.retrievalAgent());
        http.start(pendingSafetyConfig.getString("http.host"), pendingSafetyConfig.getInt("http.port"));

        DataReplayStream replay = new DataReplayStream(
            getContext().getSystem(), sharding,
            pendingSafetyConfig.getString("data.csv-path"),
            pendingSafetyConfig.getLong("data.replay-interval-ms"),
            msg.refs().fusionAgent(), msg.refs().thermalAgent(), http
        );
        replay.startReplay();

        ActorRef<InspectionProtocol.ReceiverCommand> inspectionReceiver = getContext().spawn(
            InspectionReceiverActor.create(), "inspection-receiver"
        );

   
        ActorRef<VisualInspectionAgent.Command> visualAgent = getContext().spawn(
            VisualInspectionAgent.create(inspecsafeRoot, inspecsafeInterval),
            "visual-inspection-agent"
        );
        visualAgent.tell(new VisualInspectionAgent.SetReceiverRef(inspectionReceiver));

        getContext().getLog().info("All-in-one pipeline fully wired. System ready.");
        return this;
    }


    private void startNodeA(Config safetyConfig) {
        getContext().getLog().info("Starting NODE-A: SensorSharding + Thermal + Fusion + Classification + HTTP");

        MistralClient mistral = buildMistral(safetyConfig);

    
        // One adapter for all remote service listings — Akka only allows one adapter per source type
        ActorRef<Receptionist.Listing> anyAdapter =
            getContext().messageAdapter(Receptionist.Listing.class, AnyListing::new);

        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(RETRIEVAL_KEY, anyAdapter));
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(LLM_KEY, anyAdapter));
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(ESCALATION_KEY, anyAdapter));
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(ORCHESTRATOR_KEY, anyAdapter));

        // Thermal
        ActorRef<ThermalAgent.Command> thermalAgent =
            getContext().spawn(
                ThermalAgent.create(mistral, safetyConfig.getString("mistral.vision-model")),
                "thermal-agent"
            );

        // Sharding
        ClusterSharding sharding = ClusterSharding.get(getContext().getSystem());
        sharding.init(Entity.of(SensorAgent.ENTITY_KEY,
            entityContext -> SensorAgent.create(entityContext.getEntityId())));

        // HTTP server (node-a hosts it too for replay control)
        SafetyHttpServer http = new SafetyHttpServer(getContext().getSystem());
        http.setThermalAgent(thermalAgent);
        http.setSharding(sharding);
        http.setMistral(mistral);  // available locally on node-a
        http.start(safetyConfig.getString("http.host"), safetyConfig.getInt("http.port"));
        this.httpServer = http;

        
        // Spawn ClassificationAgent with a dead-letter retrieval stub
        ActorRef<RetrievalAgent.Command> deadRetrievalStub =
            getContext().getSystem().deadLetters();

        ActorRef<ClassificationProtocol.ClassifyRequest> classificationAgent =
            getContext().spawn(
                ClassificationAgent.create(safetyConfig.getString("model.pmml-path"), deadRetrievalStub),
                "classification-agent"
            );
        this.classificationAgentRef = classificationAgent;

        // FusionAgent also starts with dead-letter stubs for remote refs
        ActorRef<LLMProtocol.Command> deadLlmStub =
            getContext().getSystem().deadLetters();
        ActorRef<EscalationProtocol.EscalationRequest> deadEscalationStub =
            getContext().getSystem().deadLetters();

        ActorRef<FusionAgent.Command> fusionAgent =
            getContext().spawn(
                FusionAgent.create(classificationAgent, deadRetrievalStub,
                    deadLlmStub, deadEscalationStub),
                "fusion-agent"
            );
        this.fusionAgentRef = fusionAgent;

        thermalAgent.tell(new ThermalAgent.SetFusionRef(fusionAgent));

        // Data replay: starts but thermal images will flow; sensor events will flow;
        DataReplayStream replay = new DataReplayStream(
            getContext().getSystem(), sharding,
            safetyConfig.getString("data.csv-path"),
            safetyConfig.getLong("data.replay-interval-ms"),
            fusionAgent, thermalAgent, http
        );
        replay.startReplay();

        // Spawn InspectionReceiverActor on node-a and register it so node-d can discover it
        String inspecsafeRoot = safetyConfig.hasPath("inspecsafe.data-path")
            ? safetyConfig.getString("inspecsafe.data-path")
            : "./inspecsafe-v1";
        http.setInspecsafeDataRoot(inspecsafeRoot);

        ActorRef<InspectionProtocol.ReceiverCommand> inspectionReceiver =
            getContext().spawn(InspectionReceiverActor.create(), "inspection-receiver");
        getContext().getSystem().receptionist().tell(
            Receptionist.register(INSPECTION_RECEIVER_KEY, inspectionReceiver));

        getContext().getLog().info("NODE-A ready. InspectionReceiverActor registered. Waiting for node-b/node-c/node-d Receptionist listings...");
    }

  
    private void startNodeB(Config safetyConfig) {
        getContext().getLog().info("Starting NODE-B: RetrievalAgent + LLMReasoningAgent + EscalationAgent");

        MistralClient mistral = buildMistral(safetyConfig);
        QdrantClient qdrant   = buildQdrant(safetyConfig);
        MongoDBService mongo  = buildMongo(safetyConfig);

        // Subscribe to node-c OrchestratorAgent listing so EscalationAgent can forward incidents
        ActorRef<Receptionist.Listing> anyAdapter =
            getContext().messageAdapter(Receptionist.Listing.class, AnyListing::new);
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(ORCHESTRATOR_KEY, anyAdapter));

        // Retrieval
        ActorRef<RetrievalAgent.Command> retrievalAgent =
            getContext().spawn(RetrievalAgent.create(qdrant, mistral), "retrieval-agent");

        // LLM — receives retrievalAgent so chat queries can search Qdrant for past incidents
        ActorRef<LLMProtocol.Command> llmReasoningAgent =
            getContext().spawn(LLMReasoningAgent.create(mistral, mongo, retrievalAgent), "llm-reasoning-agent");

        // EscalationAgent: orchestrator ref arrives from Receptionist.
        ActorRef<OrchestratorProtocol.IncidentFinalized> orchestratorForwarder =
            getContext().messageAdapter(
                OrchestratorProtocol.IncidentFinalized.class,
                inc -> {
                    // Forward to remote orchestrator when available
                    if (remoteOrchestratorAgent != null) {
                        remoteOrchestratorAgent.tell(new OrchestratorAgent.IncidentMsg(inc));
                    } else {
                        getContext().getLog().warn("OrchestratorAgent not yet available — incident dropped: {}",
                            inc.incidentId());
                    }
                    return new IncidentReceived(inc);
                }
            );
        this.orchestratorIncidentAdapter = orchestratorForwarder;

        ActorRef<EscalationProtocol.EscalationRequest> escalationAgent =
            getContext().spawn(EscalationAgent.create(orchestratorForwarder), "escalation-agent");
        this.localEscalationAgent = escalationAgent;

        // Register all three with Receptionist for cross-node discovery
        getContext().getSystem().receptionist().tell(
            Receptionist.register(RETRIEVAL_KEY, retrievalAgent));
        getContext().getSystem().receptionist().tell(
            Receptionist.register(LLM_KEY, llmReasoningAgent));
        getContext().getSystem().receptionist().tell(
            Receptionist.register(ESCALATION_KEY, escalationAgent));

        getContext().getLog().info("NODE-B ready. Registered retrieval/llm/escalation with Receptionist.");
    }

    private void startNodeC(Config safetyConfig) {
        getContext().getLog().info("Starting NODE-C: OrchestratorAgent");

        MongoDBService mongo = buildMongo(safetyConfig);

        // OrchestratorAgent needs a RetrievalAgent for Qdrant storage.
        ActorRef<Receptionist.Listing> anyAdapter =
            getContext().messageAdapter(Receptionist.Listing.class, AnyListing::new);
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(RETRIEVAL_KEY, anyAdapter));

        ActorRef<RetrievalAgent.Command> deadRetrievalStub =
            getContext().getSystem().deadLetters();

        ActorRef<OrchestratorAgent.Command> orchestratorAgent =
            getContext().spawn(OrchestratorAgent.create(deadRetrievalStub, mongo), "orchestrator-agent");

        getContext().getSystem().receptionist().tell(
            Receptionist.register(ORCHESTRATOR_KEY, orchestratorAgent));

        SafetyHttpServer http = new SafetyHttpServer(getContext().getSystem());
        http.setOrchestratorAgent(orchestratorAgent);
        http.setMongo(mongo);
        http.start(safetyConfig.getString("http.host"), safetyConfig.getInt("http.port"));
        this.httpServer = http;

        getContext().getLog().info("NODE-C ready. OrchestratorAgent registered with Receptionist.");
    }

    private void startNodeD(Config safetyConfig) {
        getContext().getLog().info("Starting NODE-D: VisualInspectionAgent");

        String inspecsafeRoot = safetyConfig.hasPath("inspecsafe.data-path")
            ? safetyConfig.getString("inspecsafe.data-path")
            : "./inspecsafe-v1";
        long inspecsafeInterval = safetyConfig.hasPath("inspecsafe.replay-interval-ms")
            ? safetyConfig.getLong("inspecsafe.replay-interval-ms")
            : 20000L;

        
        localVisualInspectionAgent = getContext().spawn(
            VisualInspectionAgent.create(inspecsafeRoot, inspecsafeInterval),
            "visual-inspection-agent"
        );

 
        ActorRef<Receptionist.Listing> receiverAdapter =
            getContext().messageAdapter(Receptionist.Listing.class, InspectionReceiverListing::new);
        getContext().getSystem().receptionist().tell(
            Receptionist.subscribe(INSPECTION_RECEIVER_KEY, receiverAdapter));

        getContext().getLog().info(
            "NODE-D ready. VisualInspectionAgent started, waiting for InspectionReceiverActor on node-a...");
    }

    // Single handler dispatches all remote service listings by key — avoids the one-adapter-per-type limit
    private Behavior<Command> onAnyListing(AnyListing msg) {
        Receptionist.Listing listing = msg.listing();

        if (listing.isForKey(RETRIEVAL_KEY)) {
            Set<ActorRef<RetrievalAgent.Command>> instances = listing.getServiceInstances(RETRIEVAL_KEY);
            if (!instances.isEmpty()) {
                remoteRetrievalAgent = instances.iterator().next();
                getContext().getLog().info("Receptionist: RetrievalAgent discovered at {}", remoteRetrievalAgent.path());
                if (httpServer != null) {
                    if (remoteLlmAgent != null) httpServer.setLlmAgent(remoteLlmAgent);
                    httpServer.setRetrievalAgent(remoteRetrievalAgent);
                }
            }

        } else if (listing.isForKey(LLM_KEY)) {
            Set<ActorRef<LLMProtocol.Command>> instances = listing.getServiceInstances(LLM_KEY);
            if (!instances.isEmpty()) {
                remoteLlmAgent = instances.iterator().next();
                getContext().getLog().info("Receptionist: LLMReasoningAgent discovered at {}", remoteLlmAgent.path());
                if (httpServer != null) httpServer.setLlmAgent(remoteLlmAgent);
            }

        } else if (listing.isForKey(ESCALATION_KEY)) {
            Set<ActorRef<EscalationProtocol.EscalationRequest>> instances = listing.getServiceInstances(ESCALATION_KEY);
            if (!instances.isEmpty()) {
                remoteEscalationAgent = instances.iterator().next();
                getContext().getLog().info("Receptionist: EscalationAgent discovered at {}", remoteEscalationAgent.path());
                // Wire FusionAgent with real Node B refs as soon as all three are available
                if (fusionAgentRef != null && remoteRetrievalAgent != null && remoteLlmAgent != null) {
                    fusionAgentRef.tell(new FusionAgent.UpdateRefs(
                        remoteRetrievalAgent, remoteLlmAgent, remoteEscalationAgent));
                }
            }

        } else if (listing.isForKey(ORCHESTRATOR_KEY)) {
            Set<ActorRef<OrchestratorAgent.Command>> instances = listing.getServiceInstances(ORCHESTRATOR_KEY);
            if (!instances.isEmpty()) {
                remoteOrchestratorAgent = instances.iterator().next();
                getContext().getLog().info("Receptionist: OrchestratorAgent discovered at {}", remoteOrchestratorAgent.path());
                if (httpServer != null) httpServer.setOrchestratorAgent(remoteOrchestratorAgent);
            }
        }

        return this;
    }

    private Behavior<Command> onInspectionReceiverListing(InspectionReceiverListing msg) {
        Set<ActorRef<InspectionProtocol.ReceiverCommand>> instances =
            msg.listing().getServiceInstances(INSPECTION_RECEIVER_KEY);
        if (!instances.isEmpty()) {
            ActorRef<InspectionProtocol.ReceiverCommand> receiverRef = instances.iterator().next();
            getContext().getLog().info(
                "Receptionist: InspectionReceiverActor discovered at {}", receiverRef.path());

            // If we are node-d, forward the ref to our VisualInspectionAgent
            if (localVisualInspectionAgent != null) {
                localVisualInspectionAgent.tell(
                    new VisualInspectionAgent.SetReceiverRef(receiverRef));
                getContext().getLog().info(
                    "NODE-D: SetReceiverRef sent to VisualInspectionAgent — cross-cluster delivery active.");
            }
        }
        return this;
    }

    private MistralClient buildMistral(Config c) {
        return new MistralClient(
            c.getString("mistral.api-key"),
            c.getString("mistral.base-url"),
            c.getString("mistral.model"),
            c.getString("mistral.embed-model")
        );
    }

    private QdrantClient buildQdrant(Config c) {
        return new QdrantClient(
            c.getString("qdrant.url"),
            c.getString("qdrant.api-key"),
            c.getString("qdrant.collection")
        );
    }

    private MongoDBService buildMongo(Config c) {
        return new MongoDBService(
            c.getString("mongodb.uri"),
            c.getString("mongodb.database")
        );
    }

    private EmailNotificationService buildEmail(Config c) {
        if (!c.hasPath("email.smtp-host") || c.getString("email.smtp-host").isBlank()) {
            return new EmailNotificationService(null, 587, null, null, null, null, true);
        }
        return new EmailNotificationService(
            c.getString("email.smtp-host"),
            c.hasPath("email.smtp-port") ? c.getInt("email.smtp-port") : 587,
            c.getString("email.smtp-username"),
            c.getString("email.smtp-password"),
            c.getString("email.from"),
            c.getString("email.to"),
            !c.hasPath("email.use-tls") || c.getBoolean("email.use-tls")
        );
    }

   
    public static void main(String[] args) {
        ActorSystem<Command> system = ActorSystem.create(
            SafetyGuardian.create(),
            "industrial-safety"
        );
        system.log().info("Industrial Safety Monitoring System started");
    }
}
