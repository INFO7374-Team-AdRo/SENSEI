package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.MongoDBService;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.*;

import java.time.Instant;
import java.util.*;

public class OrchestratorAgent extends AbstractBehavior<OrchestratorAgent.Command> {

    public interface Command {}

    public record IncidentMsg(OrchestratorProtocol.IncidentFinalized incident) implements Command {}
    public record StatusRequest(OrchestratorProtocol.GetSystemStatus request) implements Command {}
    public record GetIncidents(ActorRef<IncidentListResponse> replyTo) implements Command {}
    public record GetIncidentById(String incidentId, ActorRef<IncidentResponse> replyTo) implements Command {}

    public record IncidentListResponse(List<OrchestratorProtocol.IncidentFinalized> incidents) {}
    public record IncidentResponse(OrchestratorProtocol.IncidentFinalized incident) {}

    private final LinkedHashMap<String, OrchestratorProtocol.IncidentFinalized> incidentMap = new LinkedHashMap<>();
    private final List<EscalationProtocol.EscalationDecision> recentEscalations = new ArrayList<>();
    private int totalEventsProcessed = 0;

    private final ActorRef<RetrievalAgent.Command> retrievalAgent;
    private final MongoDBService mongo;

    public static Behavior<Command> create(ActorRef<RetrievalAgent.Command> retrievalAgent,
                                           MongoDBService mongo) {
        return Behaviors.setup(ctx -> new OrchestratorAgent(ctx, retrievalAgent, mongo));
    }

    private OrchestratorAgent(ActorContext<Command> context,
                              ActorRef<RetrievalAgent.Command> retrievalAgent,
                              MongoDBService mongo) {
        super(context);
        this.retrievalAgent = retrievalAgent;
        this.mongo = mongo;
        context.getLog().info("OrchestratorAgent started (MongoDB={})", mongo.isAvailable());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(IncidentMsg.class, this::onIncident)
            .onMessage(StatusRequest.class, this::onStatusRequest)
            .onMessage(GetIncidents.class, this::onGetIncidents)
            .onMessage(GetIncidentById.class, this::onGetIncidentById)
            .build();
    }

    private Behavior<Command> onIncident(IncidentMsg msg) {
        var inc = msg.incident();
        totalEventsProcessed++;

        if (incidentMap.size() >= 2000) {
            incidentMap.remove(incidentMap.keySet().iterator().next());
        }
        incidentMap.put(inc.incidentId(), inc);

        recentEscalations.add(inc.decision());
        if (recentEscalations.size() > 50) recentEscalations.remove(0);

        getContext().getLog().info("Incident {} finalized — tier={} class={} cause={}",
            inc.incidentId(), inc.decision().tier(),
            inc.classification().hazardClass(), inc.reasoning().causalChain());

        // Count every finalized incident (all tiers) for the total incidents counter
        SafetyHttpServer httpTotal = SafetyHttpServer.getInstance();
        if (httpTotal != null) httpTotal.incrementTotalIncidents();

        // Push to live dashboard feed — only T2_ALERT and T3_SHUTDOWN are real alerts.
        // T1_LOG events are recorded in MongoDB/Qdrant but don't increment the alert counter.
        EscalationProtocol.Tier tier = inc.decision().tier();
        if (tier == EscalationProtocol.Tier.T2_ALERT || tier == EscalationProtocol.Tier.T3_SHUTDOWN) {
            SafetyHttpServer http = SafetyHttpServer.getInstance();
            if (http != null) {
                Map<String, Object> escMap = new LinkedHashMap<>();
                escMap.put("hazardType",     inc.reasoning().hazardType());
                escMap.put("severity",       inc.reasoning().severity());
                escMap.put("recommendation", inc.reasoning().recommendation());
                escMap.put("confidence",     inc.classification().confidence());
                escMap.put("tier",           tier.name());
                escMap.put("timestamp",      inc.decision().timestamp().toString());
                http.pushEscalationEvent(escMap);
            }
        }

        // --- Qdrant: enrich RAG query with sensor readings for better similarity ---
        StringBuilder sensorSummary = new StringBuilder();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().sensorEvents() != null) {
            inc.classification().fusedEvent().sensorEvents().forEach((type, event) -> {
                if (event.thresholdBreached()) {
                    sensorSummary.append(type).append("=").append(event.valuePpm()).append("ppm ");
                }
            });
        }
        String qdrantSummary = String.format("%s event — %s — %s | sensors: %s",
            inc.classification().hazardClass(),
            inc.reasoning().causalChain(),
            inc.reasoning().recommendation(),
            sensorSummary.toString().trim());

        Map<String, Object> qdrantMeta = new HashMap<>();
        qdrantMeta.put("hazardClass", inc.classification().hazardClass());
        qdrantMeta.put("rootCause", inc.reasoning().causalChain());
        qdrantMeta.put("resolution", inc.reasoning().recommendation());
        qdrantMeta.put("severity", inc.reasoning().severity());
        qdrantMeta.put("tier", inc.decision().tier().name());

        retrievalAgent.tell(new RetrievalAgent.Store(
            new RetrievalProtocol.StoreIncident(inc.incidentId(), qdrantSummary, qdrantMeta)
        ));

        // --- MongoDB: build full document (list + report fields) ---
        Map<String, Object> mongoDoc = buildMongoDocument(inc);
        mongo.saveIncident(mongoDoc);

        return this;
    }

    private Map<String, Object> buildMongoDocument(OrchestratorProtocol.IncidentFinalized inc) {
        // Affected sensors
        List<String> affectedSensors = new ArrayList<>();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().sensorEvents() != null) {
            inc.classification().fusedEvent().sensorEvents().forEach((type, event) -> {
                if (event.thresholdBreached()) affectedSensors.add(type);
            });
        }
        // Timeline from evidence steps
        List<Map<String, String>> timeline = new ArrayList<>();
        var steps = inc.reasoning().evidenceSteps();
        for (int i = 0; i < steps.size(); i++) {
            timeline.add(Map.of("time", "T+" + (i * 5) + "s", "event", steps.get(i)));
        }
        if (timeline.isEmpty()) {
            timeline.add(Map.of("time", "T+0s", "event", inc.reasoning().causalChain()));
        }

        // Full sensor snapshot (ADC values + breach flag + trend for each sensor)
        Map<String, Object> sensorReadings = new LinkedHashMap<>();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().sensorEvents() != null) {
            // Sort sensors in canonical order so the report always shows them consistently
            String[] order = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};
            var events = inc.classification().fusedEvent().sensorEvents();
            for (String s : order) {
                var ev = events.get(s);
                if (ev != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("adc",      ev.valuePpm());
                    r.put("breached", ev.thresholdBreached());
                    r.put("trend",    ev.trendShape());
                    sensorReadings.put(s, r);
                }
            }
        }

        // Thermal snapshot
        Map<String, Object> thermalData = new LinkedHashMap<>();
        if (inc.classification().fusedEvent() != null &&
                inc.classification().fusedEvent().thermalEvent() != null) {
            var th = inc.classification().fusedEvent().thermalEvent();
            thermalData.put("temperature", Math.round(th.maxTemperature() * 10.0) / 10.0);
            thermalData.put("anomaly",     th.anomalyDetected());
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("incidentId",      inc.incidentId());
        doc.put("hazardType",      inc.reasoning().hazardType());
        doc.put("severity",        inc.reasoning().severity());
        doc.put("recommendation",  inc.reasoning().recommendation());
        doc.put("confidence",      inc.classification().confidence());
        doc.put("tier",            inc.decision().tier().name());
        doc.put("timestamp",       inc.decision().timestamp().toString());
        // Evidence steps (include similar RAG incidents embedded as steps)
        doc.put("evidenceSteps", inc.reasoning().evidenceSteps());
        doc.put("causalChain",   inc.reasoning().causalChain());
        doc.put("explanation",   inc.reasoning().explanation());
        // Report fields
        doc.put("summary",         inc.reasoning().explanation());
        doc.put("rootCause",       inc.reasoning().causalChain());
        doc.put("affectedSensors", affectedSensors);
        doc.put("sensorReadings",  sensorReadings);
        doc.put("thermalData",     thermalData);
        doc.put("timeline",        timeline);
        doc.put("generatedAt",     Instant.now().toString());
        return doc;
    }

    private Behavior<Command> onStatusRequest(StatusRequest msg) {
        var status = new OrchestratorProtocol.SystemStatus(
            7, totalEventsProcessed,
            (int) incidentMap.values().stream()
                .filter(i -> i.decision().tier() != EscalationProtocol.Tier.T1_LOG).count(),
            new ArrayList<>(recentEscalations)
        );
        msg.request().replyTo().tell(status);
        return this;
    }

    private Behavior<Command> onGetIncidents(GetIncidents msg) {
        msg.replyTo().tell(new IncidentListResponse(new ArrayList<>(incidentMap.values())));
        return this;
    }

    private Behavior<Command> onGetIncidentById(GetIncidentById msg) {
        msg.replyTo().tell(new IncidentResponse(incidentMap.get(msg.incidentId())));
        return this;
    }
}
