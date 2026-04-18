package edu.neu.safety.agents.orchestrator;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.BroadcastHub;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;
import com.typesafe.config.Config;
import edu.neu.safety.agents.classification.ClassificationProtocol;
import edu.neu.safety.agents.escalation.EscalationProtocol;
import edu.neu.safety.agents.fusion.FusionProtocol;
import edu.neu.safety.agents.reasoning.ReasoningProtocol;
import edu.neu.safety.agents.retrieval.RetrievalProtocol;
import edu.neu.safety.agents.sensor.SensorProtocol;
import edu.neu.safety.agents.thermal.ThermalProtocol;
import edu.neu.safety.cluster.AgentServiceKeys;
import edu.neu.safety.cluster.ClusterSetup;
import edu.neu.safety.external.MongoDBService;
import edu.neu.safety.http.SafetyHttpServer;
import edu.neu.safety.model.*;
import edu.neu.safety.replay.CsvReplaySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pipeline coordinator — the glue actor that makes the six specialized agents
 * act as one system.
 *
 * <p>On startup it subscribes to the Receptionist for all six {@code ServiceKey}s
 * exposed by the other nodes. Once every agent is discovered, it subscribes
 * the fusion agent to its own adapter and starts the CSV replay loop.
 *
 * <p>The per-snapshot pipeline looks like this:
 * <pre>
 *   CSV row ──▶ SensorAgent (journal)  ┐
 *                                      ├─▶ FusionAgent ─▶ ClassificationAgent
 *   CSV row ──▶ ThermalAgent           ┘                        │
 *                                                               ▼
 *                    ┌─── RetrievalAgent (Qdrant) ◀── NO_GAS? short-circuit
 *                    ▼
 *              ReasoningAgent (LLM + RAG)
 *                    │
 *                    ▼
 *              EscalationAgent (policy floor)
 *                    │
 *                    ▼
 *              finalizeIncident: SSE broadcast + Mongo archive
 *                                + Qdrant upsert (self-improving RAG)
 *                                + in-memory cache for HTTP reads
 * </pre>
 *
 * <p>All cross-agent message adapters live in the constructor so the orchestrator
 * can treat each reply as a strongly-typed command of its own protocol. The
 * three {@code pending*} maps hold partial state keyed by snapshot timestamp
 * while a row is mid-flight through the pipeline.
 */
public class OrchestratorAgent extends AbstractBehavior<OrchestratorProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private static final int INCIDENT_CACHE_LIMIT = 200;

    private final Config config;
    private final ClusterSharding sharding;
    private final Materializer materializer;
    private final MongoDBService mongoService;

    private ActorRef<ThermalProtocol.Command> thermalAgent;
    private ActorRef<FusionProtocol.Command> fusionAgent;
    private ActorRef<ClassificationProtocol.Command> classificationAgent;
    private ActorRef<RetrievalProtocol.Command> retrievalAgent;
    private ActorRef<ReasoningProtocol.Command> reasoningAgent;
    private ActorRef<EscalationProtocol.Command> escalationAgent;

    private final ActorRef<ThermalProtocol.ThermalResult> thermalAdapter;
    private final ActorRef<FusionProtocol.FusionComplete> fusionCompleteAdapter;
    private final ActorRef<ClassificationResult> classificationAdapter;
    private final ActorRef<RetrievalProtocol.RetrievalResult> retrievalAdapter;
    private final ActorRef<ReasoningProtocol.ReasoningResult> reasoningAdapter;

    private SourceQueueWithComplete<IncidentReport> eventQueue;
    private Source<IncidentReport, ?> broadcastSource;

    private final Map<Long, FusedSnapshot> pendingSnapshots = new HashMap<>();
    private final Map<Long, ClassificationResult> pendingClassifications = new HashMap<>();
    private final Map<Long, List<IncidentReport.PastIncident>> pendingRetrievals = new HashMap<>();

    // Bounded LRU-ish cache served via AskPattern by the HTTP read endpoints.
    private final LinkedHashMap<String, IncidentReport> recentIncidents = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IncidentReport> eldest) {
            return size() > INCIDENT_CACHE_LIMIT;
        }
    };

    private final AtomicInteger incidentCounter = new AtomicInteger(0);
    private boolean pipelineRunning = false;
    private boolean orchestratorReady = false;

    /**
     * Factory. Supervised with exponential backoff because the orchestrator
     * holds a lot of transient state (pending maps, the broadcast hub) and a
     * tight restart loop would not give downstream connections time to
     * settle. 2s–30s with 20% jitter matches the other agents.
     */
    public static Behavior<OrchestratorProtocol.Command> create(Config config, MongoDBService mongoService) {
        return Behaviors.supervise(
            Behaviors.<OrchestratorProtocol.Command>setup(context ->
                new OrchestratorAgent(context, config, mongoService))
        ).onFailure(SupervisorStrategy.restartWithBackoff(
            Duration.ofSeconds(2), Duration.ofSeconds(30), 0.2));
    }

    /**
     * Wire up the materializer, all five message adapters, the SSE broadcast
     * hub, and the Receptionist subscriptions. After this constructor returns
     * the agent is alive but not yet useful — it waits for {@link #onAgentDiscovered}
     * to fire once every {@code ServiceKey} has been resolved.
     */
    private OrchestratorAgent(ActorContext<OrchestratorProtocol.Command> context,
                              Config config, MongoDBService mongoService) {
        super(context);
        this.config = config;
        this.mongoService = mongoService;
        this.sharding = ClusterSharding.get(context.getSystem());
        this.materializer = Materializer.createMaterializer(context);

        this.thermalAdapter = context.messageAdapter(
            ThermalProtocol.ThermalResult.class,
            result -> new OrchestratorProtocol.InternalFusionResult(
                new FusedSnapshot(Map.of(), result.maxTemp(), result.avgTemp(),
                    result.anomaly(), result.timestampMs(), null)
            )
        );

        this.fusionCompleteAdapter = context.messageAdapter(
            FusionProtocol.FusionComplete.class,
            complete -> new OrchestratorProtocol.InternalFusionComplete(complete.snapshot())
        );

        this.classificationAdapter = context.messageAdapter(
            ClassificationResult.class,
            result -> {
                FusedSnapshot snap = null;
                Long bestKey = null;
                for (Map.Entry<Long, FusedSnapshot> e : pendingSnapshots.entrySet()) {
                    if (bestKey == null || e.getKey() > bestKey) {
                        bestKey = e.getKey();
                        snap = e.getValue();
                    }
                }
                return new OrchestratorProtocol.InternalClassificationResult(result, snap);
            }
        );

        this.retrievalAdapter = context.messageAdapter(
            RetrievalProtocol.RetrievalResult.class,
            result -> new OrchestratorProtocol.InternalRetrievalResult(
                result.incidents(), null, null
            )
        );

        this.reasoningAdapter = context.messageAdapter(
            ReasoningProtocol.ReasoningResult.class,
            result -> new OrchestratorProtocol.InternalReasoningResult(
                result, null, null, null
            )
        );

        setupEventBroadcast();
        subscribeToAgentDiscovery(context);

        log.info("OrchestratorAgent initialized — awaiting Receptionist discovery (mongo={})",
            mongoService.isAvailable());
    }

    /**
     * Register the Receptionist subscriptions for all six agent ServiceKeys.
     * The listing adapter pattern means every time an agent appears or
     * disappears on the cluster, the Receptionist sends a Listing that
     * gets converted into our {@link OrchestratorProtocol.AgentDiscovered}
     * command. We just grab the first instance for each key — there's only
     * one of each per cluster in this setup.
     */
    private void subscribeToAgentDiscovery(ActorContext<OrchestratorProtocol.Command> context) {
        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class,
            listing -> {
                if (listing.isForKey(AgentServiceKeys.THERMAL)) {
                    listing.getServiceInstances(AgentServiceKeys.THERMAL).stream().findFirst()
                        .ifPresent(ref -> thermalAgent = ref);
                } else if (listing.isForKey(AgentServiceKeys.FUSION)) {
                    listing.getServiceInstances(AgentServiceKeys.FUSION).stream().findFirst()
                        .ifPresent(ref -> fusionAgent = ref);
                } else if (listing.isForKey(AgentServiceKeys.CLASSIFICATION)) {
                    listing.getServiceInstances(AgentServiceKeys.CLASSIFICATION).stream().findFirst()
                        .ifPresent(ref -> classificationAgent = ref);
                } else if (listing.isForKey(AgentServiceKeys.RETRIEVAL)) {
                    listing.getServiceInstances(AgentServiceKeys.RETRIEVAL).stream().findFirst()
                        .ifPresent(ref -> retrievalAgent = ref);
                } else if (listing.isForKey(AgentServiceKeys.REASONING)) {
                    listing.getServiceInstances(AgentServiceKeys.REASONING).stream().findFirst()
                        .ifPresent(ref -> reasoningAgent = ref);
                } else if (listing.isForKey(AgentServiceKeys.ESCALATION)) {
                    listing.getServiceInstances(AgentServiceKeys.ESCALATION).stream().findFirst()
                        .ifPresent(ref -> escalationAgent = ref);
                }
                return new OrchestratorProtocol.AgentDiscovered();
            }
        );

        var receptionist = context.getSystem().receptionist();
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.THERMAL, listingAdapter));
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.FUSION, listingAdapter));
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.CLASSIFICATION, listingAdapter));
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.RETRIEVAL, listingAdapter));
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.REASONING, listingAdapter));
        receptionist.tell(Receptionist.subscribe(AgentServiceKeys.ESCALATION, listingAdapter));
    }

    /**
     * Stand up the SourceQueue → BroadcastHub pair that the HTTP layer uses
     * to multicast live incidents to every SSE subscriber. A 256-slot
     * dropHead buffer means slow clients never backpressure the pipeline —
     * they just miss the oldest in-flight events, which is the right trade
     * for a live monitoring dashboard.
     */
    private void setupEventBroadcast() {
        var pair = Source.<IncidentReport>queue(256, OverflowStrategy.dropHead())
            .toMat(BroadcastHub.of(IncidentReport.class, 256), Keep.both())
            .run(materializer);
        this.eventQueue = pair.first();
        this.broadcastSource = pair.second();
        SafetyHttpServer.setEventSource(broadcastSource);
        SafetyHttpServer.setOrchestrator(getContext().getSelf());
    }

    /** Main dispatch table — one handler per command in {@link OrchestratorProtocol}. */
    @Override
    public Receive<OrchestratorProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(OrchestratorProtocol.AgentDiscovered.class, this::onAgentDiscovered)
            .onMessage(OrchestratorProtocol.StartPipeline.class, this::onStartPipeline)
            .onMessage(OrchestratorProtocol.StopPipeline.class, this::onStopPipeline)
            .onMessage(OrchestratorProtocol.SimulateSensorFailure.class, this::onSimulateFailure)
            .onMessage(OrchestratorProtocol.InternalFusionResult.class, this::onFusionResult)
            .onMessage(OrchestratorProtocol.InternalFusionComplete.class, this::onFusionComplete)
            .onMessage(OrchestratorProtocol.InternalClassificationResult.class, this::onClassificationResult)
            .onMessage(OrchestratorProtocol.InternalRetrievalResult.class, this::onRetrievalResult)
            .onMessage(OrchestratorProtocol.InternalReasoningResult.class, this::onReasoningResult)
            .onMessage(OrchestratorProtocol.InternalEscalationResult.class, this::onEscalationResult)
            .onMessage(OrchestratorProtocol.GetIncidents.class, this::onGetIncidents)
            .onMessage(OrchestratorProtocol.GetIncidentById.class, this::onGetIncidentById)
            .build();
    }

    /**
     * Called every time the Receptionist tells us about a new agent. Once
     * all six are resolved we subscribe the fusion agent to our adapter and
     * self-tell a StartPipeline. The logging branch is useful during cluster
     * startup because nodes can come up in any order.
     */
    private Behavior<OrchestratorProtocol.Command> onAgentDiscovered(OrchestratorProtocol.AgentDiscovered cmd) {
        if (allAgentsDiscovered() && !orchestratorReady) {
            orchestratorReady = true;
            log.info("All 6 agents discovered via Receptionist — wiring pipeline");
            fusionAgent.tell(new FusionProtocol.Subscribe(fusionCompleteAdapter));
            getContext().getSelf().tell(new OrchestratorProtocol.StartPipeline());
        } else if (!allAgentsDiscovered()) {
            int found = 0;
            StringBuilder missing = new StringBuilder();
            if (thermalAgent != null) found++; else missing.append("Thermal ");
            if (fusionAgent != null) found++; else missing.append("Fusion ");
            if (classificationAgent != null) found++; else missing.append("Classification ");
            if (retrievalAgent != null) found++; else missing.append("Retrieval ");
            if (reasoningAgent != null) found++; else missing.append("Reasoning ");
            if (escalationAgent != null) found++; else missing.append("Escalation ");
            log.info("Agent discovery: {}/6 found — waiting for [{}]", found, missing.toString().trim());
        }
        return this;
    }

    /** True iff all six downstream agent ActorRefs are resolved. */
    private boolean allAgentsDiscovered() {
        return thermalAgent != null && fusionAgent != null && classificationAgent != null
            && retrievalAgent != null && reasoningAgent != null && escalationAgent != null;
    }

    /**
     * Kick off the CSV replay. Each row fans out to both the sharded sensor
     * entities (via {@code entityRefFor} on the cluster ShardRegion) and the
     * fusion buffer, plus a synthetic thermal tick. The tick-interval comes
     * from application.conf so we can slow the feed down for demos or speed
     * it up for stress tests. Idempotent — re-entry while running is a no-op.
     */
    private Behavior<OrchestratorProtocol.Command> onStartPipeline(OrchestratorProtocol.StartPipeline cmd) {
        if (pipelineRunning) {
            log.warn("Pipeline already running");
            return this;
        }
        if (!allAgentsDiscovered()) {
            log.warn("Cannot start pipeline — not all agents discovered");
            return this;
        }

        log.info("Starting sensor replay pipeline...");
        pipelineRunning = true;

        String csvPath = config.getString("safety.replay.csv-path");
        Duration tickInterval = config.getDuration("safety.replay.tick-interval");

        CsvReplaySource.create(csvPath, tickInterval)
            .runForeach(reading -> {
                long ts = reading.timestampMs();
                String groundTruth = normalizeLabel(reading.label());
                for (Map.Entry<String, Double> entry : reading.sensorValues().entrySet()) {
                    EntityRef<SensorProtocol.Command> sensorRef =
                        sharding.entityRefFor(ClusterSetup.SENSOR_ENTITY_KEY, entry.getKey());
                    sensorRef.tell(new SensorProtocol.IngestReading(
                        entry.getKey(), entry.getValue(), ts
                    ));
                    fusionAgent.tell(new FusionProtocol.SensorUpdate(
                        entry.getKey(), entry.getValue(), ts, groundTruth
                    ));
                }
                thermalAgent.tell(new ThermalProtocol.ProcessSynthetic(ts, thermalAdapter));
            }, materializer)
            .thenRun(() -> {
                log.info("CSV replay completed");
                pipelineRunning = false;
            })
            .exceptionally(e -> {
                log.error("Pipeline error: {}", e.getMessage());
                pipelineRunning = false;
                return null;
            });

        return this;
    }

    /** Flip the running flag; the stream drains on its own. */
    private Behavior<OrchestratorProtocol.Command> onStopPipeline(OrchestratorProtocol.StopPipeline cmd) {
        log.info("Stopping pipeline");
        pipelineRunning = false;
        return this;
    }

    /**
     * Dashboard fault-injection hook. We stop the sharded sensor entity;
     * the very next IngestReading will cause Akka Cluster Sharding to
     * rehydrate it from its event journal. That's the recovery demo — any
     * state the entity had before the crash is replayed from events.
     */
    private Behavior<OrchestratorProtocol.Command> onSimulateFailure(OrchestratorProtocol.SimulateSensorFailure cmd) {
        log.warn("Simulating failure for sensor: {}", cmd.sensorType());
        EntityRef<SensorProtocol.Command> sensorRef =
            sharding.entityRefFor(ClusterSetup.SENSOR_ENTITY_KEY, cmd.sensorType());
        // Stop the entity so the next message rehydrates it from the journal — proves recovery.
        sensorRef.tell(new SensorProtocol.Stop());
        return this;
    }

    /**
     * Thermal agent replied. Forward the temps into the fusion buffer so it
     * lines up with the sensor updates for the same timestamp window.
     */
    private Behavior<OrchestratorProtocol.Command> onFusionResult(OrchestratorProtocol.InternalFusionResult cmd) {
        fusionAgent.tell(new FusionProtocol.ThermalUpdate(
            cmd.snapshot().maxTemp(), cmd.snapshot().avgTemp(),
            cmd.snapshot().thermalAnomaly(), cmd.snapshot().timestampMs()
        ));
        return this;
    }

    /**
     * Fusion finished aggregating a 3-second window. Stash the snapshot so we
     * can pair it with the classifier's reply later, then fire off the
     * Classify command.
     */
    private Behavior<OrchestratorProtocol.Command> onFusionComplete(
            OrchestratorProtocol.InternalFusionComplete cmd) {
        FusedSnapshot snapshot = cmd.snapshot();
        pendingSnapshots.put(snapshot.timestampMs(), snapshot);
        classificationAgent.tell(new ClassificationProtocol.Classify(snapshot, classificationAdapter));
        return this;
    }

    /**
     * Classification came back. If the model is highly confident (>0.90)
     * that this is NO_GAS we short-circuit — emit a cheap "no-op" report so
     * the dashboard still has a heartbeat, but skip Qdrant/LLM/Mongo entirely.
     * Otherwise we move into the RAG phase by asking RetrievalAgent for
     * similar past incidents.
     */
    private Behavior<OrchestratorProtocol.Command> onClassificationResult(
            OrchestratorProtocol.InternalClassificationResult cmd) {
        if (cmd.result() == null || cmd.snapshot() == null) {
            log.debug("Classification missing snapshot — skipping");
            return this;
        }
        ClassificationResult result = cmd.result();
        FusedSnapshot snapshot = cmd.snapshot();
        long key = snapshot.timestampMs();

        if (result.label().equals("NO_GAS") && result.confidence() > 0.90) {
            pendingSnapshots.remove(key);
            emitNoOpReport(result, snapshot);
            return this;
        }

        retrievalAgent.tell(new RetrievalProtocol.SearchSimilar(result, snapshot, retrievalAdapter));
        pendingClassifications.put(key, result);
        return this;
    }

    /**
     * RetrievalAgent returned past incidents (Qdrant hits or fallback bank).
     * We don't try to key the reply back to the original snapshot — we just
     * attach it to the oldest pending classification. Works because the
     * pipeline is sequential per snapshot at this point.
     */
    private Behavior<OrchestratorProtocol.Command> onRetrievalResult(
            OrchestratorProtocol.InternalRetrievalResult cmd) {
        if (pendingClassifications.isEmpty()) return this;

        Map.Entry<Long, ClassificationResult> entry = pendingClassifications.entrySet().iterator().next();
        long key = entry.getKey();
        ClassificationResult classification = entry.getValue();
        FusedSnapshot snapshot = pendingSnapshots.get(key);
        if (snapshot == null) return this;

        pendingRetrievals.put(key, cmd.incidents());
        reasoningAgent.tell(new ReasoningProtocol.AnalyzeIncident(
            classification, snapshot, cmd.incidents(), reasoningAdapter
        ));
        return this;
    }

    /**
     * LLM analysis returned. We evaluate the escalation tier using an
     * <em>inline</em> one-shot message adapter so we can capture the pending
     * snapshot/classification/pastIncidents in a closure and build the
     * IncidentReport at the moment the escalation decision arrives. This
     * avoids another round of pending-map lookups.
     */
    private Behavior<OrchestratorProtocol.Command> onReasoningResult(
            OrchestratorProtocol.InternalReasoningResult cmd) {
        if (pendingClassifications.isEmpty()) return this;

        Map.Entry<Long, ClassificationResult> entry = pendingClassifications.entrySet().iterator().next();
        long key = entry.getKey();
        ClassificationResult classification = entry.getValue();
        FusedSnapshot snapshot = pendingSnapshots.get(key);
        List<IncidentReport.PastIncident> pastIncidents = pendingRetrievals.getOrDefault(key, List.of());
        if (snapshot == null) return this;

        escalationAgent.tell(new EscalationProtocol.Evaluate(
            cmd.reasoning(), classification,
            getContext().messageAdapter(EscalationProtocol.EscalationDecision.class, decision -> {
                IncidentReport report = buildIncidentReport(
                    classification, snapshot, cmd.reasoning(), decision, pastIncidents
                );
                return new OrchestratorProtocol.InternalEscalationResult(decision, report);
            })
        ));
        return this;
    }

    /**
     * End of the pipeline. Finalize the incident (broadcast + Mongo + Qdrant
     * upsert + cache) and flush the pending maps for this snapshot so memory
     * doesn't grow unbounded over long runs.
     */
    private Behavior<OrchestratorProtocol.Command> onEscalationResult(
            OrchestratorProtocol.InternalEscalationResult cmd) {
        if (cmd.report() != null) {
            finalizeIncident(cmd.report());
            if (cmd.decision().tier() != EscalationTier.NONE) {
                log.info("Incident #{}: {} [{}] — {}",
                    cmd.report().id(), cmd.report().classificationLabel(),
                    cmd.decision().tier(), cmd.decision().action());
            }
        }
        if (!pendingClassifications.isEmpty()) {
            Long key = pendingClassifications.keySet().iterator().next();
            pendingSnapshots.remove(key);
            pendingClassifications.remove(key);
            pendingRetrievals.remove(key);
        }
        return this;
    }

    /**
     * AskPattern handler for the HTTP layer's {@code GET /incidents} route.
     * Returns the cache contents reversed so newest-first matches what the
     * dashboard expects.
     */
    private Behavior<OrchestratorProtocol.Command> onGetIncidents(OrchestratorProtocol.GetIncidents cmd) {
        List<IncidentReport> snapshot = new ArrayList<>(recentIncidents.values());
        Collections.reverse(snapshot);
        cmd.replyTo().tell(new OrchestratorProtocol.IncidentListResponse(snapshot));
        return this;
    }

    /** AskPattern handler for {@code GET /incidents/{id}}; null reply if not cached. */
    private Behavior<OrchestratorProtocol.Command> onGetIncidentById(OrchestratorProtocol.GetIncidentById cmd) {
        cmd.replyTo().tell(new OrchestratorProtocol.IncidentResponse(recentIncidents.get(cmd.incidentId())));
        return this;
    }

    /**
     * Close out an incident: cache it, push it onto the SSE hub, write it to
     * MongoDB asynchronously (driver calls would block the actor thread), and
     * upsert back into Qdrant — but only when it's a real incident, not a
     * NO_GAS no-op. That asymmetry is what keeps the RAG corpus useful
     * instead of drowning it in noise.
     */
    private void finalizeIncident(IncidentReport report) {
        recentIncidents.put(report.id(), report);
        eventQueue.offer(report);

        // Persist to MongoDB off the actor thread — driver calls block.
        if (mongoService.isAvailable()) {
            CompletableFuture.runAsync(() -> mongoService.saveIncident(report));
        }

        // Self-improving RAG: only push real incidents back into Qdrant.
        if (report.escalationTier() != EscalationTier.NONE && retrievalAgent != null) {
            retrievalAgent.tell(new RetrievalProtocol.StoreIncident(report));
        }
    }

    /**
     * Build a cheap "nothing happened" incident for high-confidence NO_GAS
     * snapshots. The dashboard still gets a heartbeat that proves the
     * pipeline is alive, but we deliberately skip the LLM, Mongo, and Qdrant
     * — those would cost API calls and pollute the corpus for no gain.
     */
    private void emitNoOpReport(ClassificationResult classification, FusedSnapshot snapshot) {
        String id = nextIncidentId();
        IncidentReport report = new IncidentReport(
            id,
            snapshot.sensorValues(), snapshot.maxTemp(), snapshot.avgTemp(),
            classification.label(), classification.confidence(),
            snapshot.groundTruthLabel(),
            "Normal operations", "No action required",
            EscalationTier.NONE, "Normal monitoring",
            List.of(), defaultAgentStates(),
            System.currentTimeMillis()
        );
        // Cache + broadcast, but skip MongoDB/Qdrant for normal traffic.
        recentIncidents.put(id, report);
        eventQueue.offer(report);
    }

    /**
     * Assemble the full IncidentReport from every stage's output. Called
     * from inside the inline message adapter in {@link #onReasoningResult}
     * so we have all the pieces in scope already.
     */
    private IncidentReport buildIncidentReport(
            ClassificationResult classification, FusedSnapshot snapshot,
            ReasoningProtocol.ReasoningResult reasoning,
            EscalationProtocol.EscalationDecision decision,
            List<IncidentReport.PastIncident> pastIncidents) {
        return new IncidentReport(
            nextIncidentId(),
            snapshot.sensorValues(), snapshot.maxTemp(), snapshot.avgTemp(),
            classification.label(), classification.confidence(),
            snapshot.groundTruthLabel(),
            reasoning.analysis(), reasoning.recommendation(),
            decision.tier(), decision.action(),
            pastIncidents, defaultAgentStates(),
            System.currentTimeMillis()
        );
    }

    /**
     * Coerce the CSV's free-form labels ("No Gas", "Smoke", "Perfume") into
     * the uppercase underscore form the model emits ("NO_GAS", "SMOKE"). We
     * keep this here rather than in the replay source because it's specific
     * to how the UCI gas-sensor dataset names its classes.
     */
    private static String normalizeLabel(String raw) {
        if (raw == null) return null;
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case "NO GAS", "NOGAS", "NO_GAS" -> "NO_GAS";
            case "SMOKE" -> "SMOKE";
            case "PERFUME" -> "PERFUME";
            case "COMBINED" -> "COMBINED";
            default -> upper.replace(' ', '_');
        };
    }

    /** Monotonic incident ID like "INC-00042". AtomicInteger handles concurrent callers. */
    private String nextIncidentId() {
        return "INC-" + String.format("%05d", incidentCounter.incrementAndGet());
    }

    /**
     * Snapshot of which agents are active at incident-creation time. Right
     * now this is hard-coded "all active" — once per-agent liveness probes
     * are wired in the dashboard, this should query each agent's status
     * adapter instead.
     */
    private Map<String, String> defaultAgentStates() {
        Map<String, String> agentStates = new LinkedHashMap<>();
        agentStates.put("SensorAgent", "active");
        agentStates.put("ThermalAgent", "active");
        agentStates.put("FusionAgent", "active");
        agentStates.put("ClassificationAgent", "active");
        agentStates.put("RetrievalAgent", "active");
        agentStates.put("ReasoningAgent", "active");
        agentStates.put("EscalationAgent", "active");
        agentStates.put("OrchestratorAgent", "active");
        return agentStates;
    }
}
