package edu.neu.safety.agents.orchestrator;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.IncidentReport;

import java.util.List;

/**
 * Message contract for the {@link OrchestratorAgent}.
 *
 * <p>This protocol is larger than the per-agent ones because the orchestrator
 * is the glue actor — it receives commands from the HTTP layer, receptionist
 * listings from the cluster, and relay messages from every downstream agent
 * via message adapters. The {@code Internal*} records are the adapter
 * targets: each downstream reply gets wrapped into one of these and routed
 * back through {@code createReceive()}.
 */
public interface OrchestratorProtocol {

    /** Parent marker. External commands + internal adapter messages + read queries. */
    sealed interface Command extends CborSerializable permits
        StartPipeline, StopPipeline, SimulateSensorFailure,
        AgentDiscovered,
        InternalFusionResult, InternalFusionComplete, InternalClassificationResult,
        InternalRetrievalResult, InternalReasoningResult, InternalEscalationResult,
        GetIncidents, GetIncidentById {}

    /** Fire the CSV replay loop once every agent is discovered. */
    record StartPipeline() implements Command {}

    /** Halt the replay loop (the Source.runForeach will naturally drain). */
    record StopPipeline() implements Command {}

    /** Dashboard fault-injection — stop a sharded sensor entity on demand. */
    record SimulateSensorFailure(String sensorType) implements Command {}

    /** Heartbeat from the Receptionist listing adapter — one of the six keys resolved. */
    record AgentDiscovered() implements Command {}

    // --- Adapter payloads from downstream agents ---------------------------

    /** Thermal agent replied with a temp/anomaly snapshot — fan it into the fusion buffer. */
    record InternalFusionResult(
        edu.neu.safety.model.FusedSnapshot snapshot
    ) implements Command {}

    /** Fusion agent finished a 3-second window — kick the classifier. */
    record InternalFusionComplete(
        edu.neu.safety.model.FusedSnapshot snapshot
    ) implements Command {}

    /** ONNX/rule-based classifier replied — decide whether to proceed or short-circuit NO_GAS. */
    record InternalClassificationResult(
        edu.neu.safety.model.ClassificationResult result,
        edu.neu.safety.model.FusedSnapshot snapshot
    ) implements Command {}

    /** Qdrant similarity search returned — hand it off to the LLM for RAG-grounded reasoning. */
    record InternalRetrievalResult(
        java.util.List<IncidentReport.PastIncident> incidents,
        edu.neu.safety.model.ClassificationResult classification,
        edu.neu.safety.model.FusedSnapshot snapshot
    ) implements Command {}

    /** LLM analysis is back — evaluate the escalation tier with policy + LLM suggestion. */
    record InternalReasoningResult(
        edu.neu.safety.agents.reasoning.ReasoningProtocol.ReasoningResult reasoning,
        edu.neu.safety.model.ClassificationResult classification,
        edu.neu.safety.model.FusedSnapshot snapshot,
        java.util.List<IncidentReport.PastIncident> pastIncidents
    ) implements Command {}

    /** Escalation tier chosen — finalize the incident (broadcast + persist + RAG upsert). */
    record InternalEscalationResult(
        edu.neu.safety.agents.escalation.EscalationProtocol.EscalationDecision decision,
        IncidentReport report
    ) implements Command {}

    // --- Read queries served via AskPattern from the HTTP layer ------------

    /** Return the in-memory cache of recent incidents (newest first). */
    record GetIncidents(ActorRef<IncidentListResponse> replyTo) implements Command {}

    /** Look up a single cached incident by ID. */
    record GetIncidentById(String incidentId, ActorRef<IncidentResponse> replyTo) implements Command {}

    /** Reply payload for {@link GetIncidents}. */
    record IncidentListResponse(List<IncidentReport> incidents) implements CborSerializable {}

    /** Reply payload for {@link GetIncidentById}. {@code incident} may be null. */
    record IncidentResponse(IncidentReport incident) implements CborSerializable {}
}
