package edu.neu.safety.http;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.neu.safety.agents.orchestrator.OrchestratorProtocol;
import edu.neu.safety.agents.reasoning.ReasoningProtocol;
import edu.neu.safety.agents.sensor.SensorProtocol;
import edu.neu.safety.cluster.AgentServiceKeys;
import edu.neu.safety.cluster.ClusterSetup;
import edu.neu.safety.external.MongoDBService;
import edu.neu.safety.model.IncidentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The HTTP edge of the system. This is the only public surface — every
 * other component of SENSEI is an actor or a background service, and the
 * dashboard / demo scripts all talk to the system through the routes wired
 * up here.
 *
 * <p>Routes exposed:
 * <ul>
 *   <li>{@code GET  /events}            — Server-Sent Events stream of finalized
 *       incidents from the orchestrator's BroadcastHub.</li>
 *   <li>{@code GET  /api-incidents}     — paginated list of finalized incidents,
 *       served from MongoDB when it's up and falling back to the orchestrator's
 *       in-memory buffer otherwise.</li>
 *   <li>{@code GET  /api-report/{id}}   — one incident in full detail.</li>
 *   <li>{@code GET  /api-status}        — cluster/LLM counters for the "system
 *       health" panel.</li>
 *   <li>{@code POST /api-chat}          — natural-language query routed to the
 *       reasoning agent.</li>
 *   <li>{@code GET  /api-fault/status}  — per-shard alive / recovering state
 *       for the fault-tolerance demo panel.</li>
 *   <li>{@code POST /api-fault/kill/{type}} — force-stop a SensorAgent shard
 *       to show sharded recovery.</li>
 *   <li>{@code GET  /dashboard/*}       — static HTML/JS/CSS.</li>
 * </ul>
 *
 * <p>Everything routes through CORS-permissive headers so the dashboard can
 * run from the built-in static mount or from a separate dev server during
 * UI iteration.
 */
public final class SafetyHttpServer extends AllDirectives {

    private static final Logger log = LoggerFactory.getLogger(SafetyHttpServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] SENSOR_TYPES =
        {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};

    private static volatile Source<IncidentReport, ?> eventSource;
    private static final AtomicReference<ActorRef<OrchestratorProtocol.Command>> ORCHESTRATOR =
        new AtomicReference<>();
    private static final AtomicReference<ActorRef<ReasoningProtocol.Command>> REASONING =
        new AtomicReference<>();

    private final ActorSystem<?> system;
    private final MongoDBService mongo;
    private final ClusterSharding sharding;
    private final Map<String, Instant> killedAt = new ConcurrentHashMap<>();

    /** Called by the orchestrator once its BroadcastHub is materialized. */
    public static void setEventSource(Source<IncidentReport, ?> source) {
        eventSource = source;
    }

    /** Hot-swap the orchestrator ref — useful after a node restart. */
    public static void setOrchestrator(ActorRef<OrchestratorProtocol.Command> ref) {
        ORCHESTRATOR.set(ref);
    }

    /**
     * Bind the HTTP server on the given host/port and start serving all
     * routes. Fire-and-forget — the returned binding is just logged.
     *
     * @param system       actor system to use for HTTP + scheduling
     * @param host         interface to bind on (typically "0.0.0.0")
     * @param port         TCP port, pulled from application.conf
     * @param orchestrator ActorRef the routes will ask for incidents / status
     * @param mongo        MongoDB service for historical queries (may be disabled)
     */
    public static void start(ActorSystem<?> system, String host, int port,
                             ActorRef<OrchestratorProtocol.Command> orchestrator,
                             MongoDBService mongo) {
        ORCHESTRATOR.set(orchestrator);
        SafetyHttpServer server = new SafetyHttpServer(system, mongo);
        server.subscribeReasoningAgent();

        Http.get(system).newServerAt(host, port)
            .bind(server.createRoutes())
            .whenComplete((binding, err) -> {
                if (err != null) {
                    log.error("HTTP server failed: {}", err.getMessage());
                } else {
                    log.info("HTTP server started at http://{}:{}", host, port);
                    log.info("  Dashboard: http://{}:{}/dashboard/index.html", host, port);
                    log.info("  SSE: http://{}:{}/events", host, port);
                }
            });
    }

    private SafetyHttpServer(ActorSystem<?> system, MongoDBService mongo) {
        this.system = system;
        this.mongo = mongo;
        this.sharding = ClusterSharding.get(system);
    }

    /**
     * Spin up a tiny internal actor whose only job is to listen for
     * Receptionist updates about the reasoning agent and store the current
     * ActorRef in {@link #REASONING}. That way /api-chat works regardless
     * of which node the reasoning agent is running on.
     */
    private void subscribeReasoningAgent() {
        // Standalone Receptionist subscriber so /api-chat can route NL queries to whichever
        // node currently hosts the reasoning agent (it may live on a different cluster node).
        ActorRef<Receptionist.Listing> listener = system.systemActorOf(
            Behaviors.<Receptionist.Listing>setup(ctx -> Behaviors.receiveMessage(listing -> {
                if (listing.isForKey(AgentServiceKeys.REASONING)) {
                    listing.getServiceInstances(AgentServiceKeys.REASONING).stream()
                        .findFirst().ifPresent(REASONING::set);
                }
                return Behaviors.same();
            })),
            "http-reasoning-discovery",
            akka.actor.typed.Props.empty()
        );
        system.receptionist().tell(Receptionist.subscribe(AgentServiceKeys.REASONING, listener));
    }

    /** Top-level route composition — wraps every child route with CORS headers. */
    private Route createRoutes() {
        return respondWithHeaders(
            List.of(
                RawHeader.create("Access-Control-Allow-Origin", "*"),
                RawHeader.create("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
                RawHeader.create("Access-Control-Allow-Headers", "Content-Type")
            ),
            () -> concat(
                options(() -> complete(StatusCodes.OK)),
                sseRoute(),
                healthRoute(),
                statusRoute(),
                incidentsRoute(),
                reportRoute(),
                chatRoute(),
                faultRoutes(),
                staticRoutes()
            )
        );
    }

    /**
     * Server-Sent Events endpoint. Maps the orchestrator's incident Source
     * into SSE-framed byte strings and sends a heartbeat every 15 seconds
     * so intermediate proxies don't time the connection out.
     */
    private Route sseRoute() {
        return path("events", () -> get(() -> {
            if (eventSource == null) {
                return complete(StatusCodes.OK, "data: {\"status\":\"waiting\"}\n\n");
            }
            Source<ByteString, ?> sseBytes = eventSource
                .map(report -> {
                    try {
                        String json = mapper.writeValueAsString(report);
                        return ByteString.fromString("event: incident\ndata: " + json + "\n\n");
                    } catch (Exception e) {
                        return ByteString.fromString("event: error\ndata: {}\n\n");
                    }
                })
                .keepAlive(Duration.ofSeconds(15),
                    () -> ByteString.fromString(": heartbeat\n\n"));

            ContentType.WithFixedCharset sseContentType =
                MediaTypes.TEXT_EVENT_STREAM.toContentType();

            return complete(HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(HttpEntities.create(sseContentType, sseBytes)));
        }));
    }

    /** Trivial liveness probe — used by load balancers and by the launch script. */
    private Route healthRoute() {
        return path("health", () -> get(() ->
            jsonOk("{\"status\":\"ok\",\"service\":\"sensei\"}")
        ));
    }

    /**
     * /api-status returns enough counters to populate the dashboard's
     * "System Health" panel. Fires an ask to the reasoning agent to pull
     * live LLM call / fallback counters, with a short timeout so the HTTP
     * request never hangs waiting for a slow Mistral call.
     */
    private Route statusRoute() {
        return path("api-status", () -> get(() -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("pipeline", ORCHESTRATOR.get() != null ? "running" : "starting");
            node.put("agents", 8);
            node.put("sensorShards", 7);
            node.put("mongoAvailable", mongo != null && mongo.isAvailable());
            node.put("reasoningAvailable", REASONING.get() != null);

            ActorRef<ReasoningProtocol.Command> reasoning = REASONING.get();
            if (reasoning != null) {
                try {
                    ReasoningProtocol.ReasoningStatus rs = AskPattern.<ReasoningProtocol.Command, ReasoningProtocol.ReasoningStatus>ask(
                        reasoning,
                        ReasoningProtocol.GetStatus::new,
                        Duration.ofSeconds(2), system.scheduler()
                    ).toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
                    ObjectNode llm = node.putObject("llm");
                    llm.put("analysesCompleted", rs.analysesCompleted());
                    llm.put("mistralCalls", rs.llmCalls());
                    llm.put("rulebasedFallbacks", rs.fallbacksUsed());
                    llm.put("nlQueriesAnswered", rs.nlQueriesAnswered());
                } catch (Exception ignored) {}
            }
            return jsonOk(node.toString());
        }));
    }

    /**
     * List all known incidents. MongoDB is the source of truth when
     * available; otherwise we fall back to the orchestrator's in-memory
     * rolling buffer so the demo still has something to show.
     */
    private Route incidentsRoute() {
        return path("api-incidents", () -> get(() -> {
            if (mongo != null && mongo.isAvailable()) {
                CompletionStage<List<Map<String, Object>>> future =
                    CompletableFuture.supplyAsync(mongo::getAllIncidents);
                return onSuccess(future, docs -> jsonOk(safeJson(docs)));
            }
            ActorRef<OrchestratorProtocol.Command> orch = ORCHESTRATOR.get();
            if (orch == null) return jsonOk("[]");
            CompletionStage<OrchestratorProtocol.IncidentListResponse> future = AskPattern.ask(
                orch,
                OrchestratorProtocol.GetIncidents::new,
                Duration.ofSeconds(5), system.scheduler());
            return onSuccess(future, resp -> jsonOk(safeJson(resp.incidents())));
        }));
    }

    /**
     * Single-incident detail endpoint. Tries Mongo first, then falls back
     * to an ask against the orchestrator if Mongo doesn't have the id —
     * that way a very recent incident that hasn't been flushed yet still
     * resolves.
     */
    private Route reportRoute() {
        return path(akka.http.javadsl.server.PathMatchers.segment("api-report")
                .slash(akka.http.javadsl.server.PathMatchers.segment()), incidentId ->
            get(() -> {
                if (mongo != null && mongo.isAvailable()) {
                    CompletionStage<Map<String, Object>> future =
                        CompletableFuture.supplyAsync(() -> mongo.getIncidentById(incidentId));
                    return onSuccess(future, doc -> {
                        if (doc != null) return jsonOk(safeJson(doc));
                        return askOrchestratorForIncident(incidentId);
                    });
                }
                return askOrchestratorForIncident(incidentId);
            })
        );
    }

    /** Helper — go ask the orchestrator for an incident we couldn't find in Mongo. */
    private Route askOrchestratorForIncident(String id) {
        ActorRef<OrchestratorProtocol.Command> orch = ORCHESTRATOR.get();
        if (orch == null) return jsonNotFound("orchestrator unavailable");
        CompletionStage<OrchestratorProtocol.IncidentResponse> future = AskPattern.ask(
            orch,
            replyTo -> new OrchestratorProtocol.GetIncidentById(id, replyTo),
            Duration.ofSeconds(5), system.scheduler());
        return onSuccess(future, resp -> {
            if (resp.incident() == null) return jsonNotFound("Incident not found");
            return jsonOk(safeJson(resp.incident()));
        });
    }

    /**
     * /api-chat — NL query from the operator. Before handing off to the
     * reasoning agent we pull the orchestrator's recent-incidents buffer
     * so the LLM has real telemetry to ground on instead of hallucinating.
     */
    private Route chatRoute() {
        return path("api-chat", () -> post(() ->
            extractRequest(req ->
                onSuccess(req.entity().toStrict(5000, system), strict -> {
                    String body = strict.getData().utf8String();
                    String query = "What is the current system status?";
                    String conversationId = "default";
                    try {
                        var json = mapper.readTree(body);
                        if (json.has("query")) query = json.get("query").asText();
                        if (json.has("conversationId")) conversationId = json.get("conversationId").asText();
                    } catch (Exception ignored) {}

                    ActorRef<ReasoningProtocol.Command> reasoning = REASONING.get();
                    if (reasoning == null) {
                        return jsonOk("{\"answer\":\"Reasoning agent not yet discovered\"," +
                            "\"queryType\":\"error\",\"sourcesUsed\":[]}");
                    }
                    // Ground the LLM in live telemetry — pull the orchestrator's recent-incidents
                    // window so the NL prompt has real data instead of producing placeholder text.
                    List<IncidentReport> recent = fetchRecentIncidentsForChat();
                    final String fq = query, fc = conversationId;
                    CompletionStage<ReasoningProtocol.NLResponse> future = AskPattern.ask(
                        reasoning,
                        replyTo -> new ReasoningProtocol.NLQuery(fq, fc, recent, replyTo),
                        Duration.ofSeconds(30), system.scheduler());
                    return onSuccess(future, resp -> {
                        ObjectNode out = mapper.createObjectNode();
                        out.put("answer", resp.answer());
                        out.put("queryType", resp.queryType());
                        var arr = out.putArray("sourcesUsed");
                        for (String s : resp.sourcesUsed()) arr.add(s);
                        return jsonOk(out.toString());
                    });
                })
            )
        ));
    }

    /** Short-timeout ask for the recent incidents list — returns an empty list on any failure. */
    private List<IncidentReport> fetchRecentIncidentsForChat() {
        ActorRef<OrchestratorProtocol.Command> orch = ORCHESTRATOR.get();
        if (orch == null) return List.of();
        try {
            CompletionStage<OrchestratorProtocol.IncidentListResponse> future = AskPattern.ask(
                orch,
                OrchestratorProtocol.GetIncidents::new,
                Duration.ofSeconds(2), system.scheduler());
            List<IncidentReport> list = future.toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).incidents();
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("chat: failed to fetch recent incidents: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Routes powering the "Fault Tolerance Demo" panel. {@code /status}
     * advertises which shards are alive/killed/recovering; {@code /kill/X}
     * stops sensor shard X so Akka Cluster Sharding can re-incarnate it on
     * the next message and we can show the replay visually on the dashboard.
     */
    private Route faultRoutes() {
        return pathPrefix("api-fault", () -> concat(
            path("status", () -> get(() -> {
                Map<String, Object> shards = new LinkedHashMap<>();
                for (String sensor : SENSOR_TYPES) {
                    Map<String, Object> info = new HashMap<>();
                    Instant killed = killedAt.get(sensor);
                    String status;
                    if (killed == null) {
                        status = "alive";
                    } else {
                        long ms = Duration.between(killed, Instant.now()).toMillis();
                        if (ms < 800) status = "killed";
                        else if (ms < 4000) status = "recovering";
                        else { status = "alive"; killedAt.remove(sensor); }
                    }
                    info.put("status", status);
                    if (killed != null) info.put("killedAt", killed.toString());
                    shards.put(sensor, info);
                }
                return jsonOk(safeJson(Map.of("shards", shards)));
            })),
            path(akka.http.javadsl.server.PathMatchers.segment("kill")
                    .slash(akka.http.javadsl.server.PathMatchers.segment()), sensorType ->
                post(() -> {
                    sharding.entityRefFor(ClusterSetup.SENSOR_ENTITY_KEY, sensorType)
                        .tell(new SensorProtocol.Stop());
                    killedAt.put(sensorType, Instant.now());
                    log.warn("FAULT DEMO: SensorAgent [{}] stopped — sharding will reincarnate on next message",
                        sensorType);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("killed", sensorType);
                    resp.put("timestamp", Instant.now().toString());
                    resp.put("note", "Akka Cluster Sharding will auto-restart on next IngestReading (~200ms)");
                    return jsonOk(safeJson(resp));
                })
            )
        ));
    }

    /** Mount /dashboard from the on-disk directory and redirect / to /dashboard/index.html. */
    private Route staticRoutes() {
        return concat(
            pathPrefix("dashboard", () -> getFromDirectory("dashboard")),
            pathSingleSlash(() ->
                redirect(Uri.create("/dashboard/index.html"), StatusCodes.TEMPORARY_REDIRECT))
        );
    }

    /** Convenience: wrap a JSON body in a 200 OK response with the right content type. */
    private Route jsonOk(String body) {
        return complete(HttpResponse.create()
            .withStatus(StatusCodes.OK)
            .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, body)));
    }

    /** Same idea as {@link #jsonOk} but for a 404 with a small error envelope. */
    private Route jsonNotFound(String msg) {
        return complete(HttpResponse.create()
            .withStatus(StatusCodes.NOT_FOUND)
            .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON,
                "{\"error\":\"" + msg + "\"}")));
    }

    /**
     * Serialize any object to JSON, returning a small error envelope if
     * Jackson blows up (unprintable types, circular refs, etc). Keeps the
     * endpoint from returning a 500 for a formatting glitch.
     */
    private String safeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
