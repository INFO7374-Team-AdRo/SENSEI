package com.safety.protocol;

import akka.actor.typed.ActorRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class LLMProtocol {

    // Unified command interface
    public interface Command extends CborSerializable {}

    // --- Capability 1: Multi-step reasoning ---

    public record ReasoningRequest(
        RetrievalProtocol.RetrievalResult context,
        ActorRef<ReasoningResult> replyTo
    ) implements Command {}

    public record RequestSensorHistory(
        String sensorType,
        int lastNReadings,
        ActorRef<ToolResponse> replyTo
    ) implements CborSerializable {}

    public record RequestRetrievalContext(
        String queryText,
        int topK,
        ActorRef<ToolResponse> replyTo
    ) implements CborSerializable {}

    public record RequestIncidentCount(
        String hazardClass,
        ActorRef<ToolResponse> replyTo
    ) implements CborSerializable {}

    public record ToolResponse(
        String toolName,
        String resultJson
    ) implements Command {}

    // --- Capability 2: Root cause analysis (part of ReasoningResult) ---

    public record ReasoningResult(
        String hazardType,
        String severity,
        String causalChain,
        List<String> evidenceSteps,
        String recommendation,
        String explanation,
        int reasoningSteps,
        Instant timestamp
    ) implements CborSerializable {}

    // --- Capability 3: Post-incident report generation ---

    public record GenerateReport(
        String incidentId,
        List<ReasoningResult> eventHistory,
        Map<String, List<Integer>> sensorTimeline,
        List<EscalationProtocol.EscalationDecision> escalationHistory,
        ActorRef<IncidentReport> replyTo
    ) implements Command {}

    public record IncidentReport(
        String incidentId,
        String summary,
        String timeline,
        String rootCause,
        String actionsTaken,
        String recommendations,
        String fullReport
    ) implements CborSerializable {}

    // --- Capability 4: Natural language querying ---

    public record NLQuery(
        String query,
        String conversationId,
        String liveContext,       // current sensor/escalation state injected by SafetyHttpServer
        ActorRef<NLResponse> replyTo
    ) implements Command {}

    public record NLResponse(
        String answer,
        String queryType,
        List<String> sourcesUsed
    ) implements CborSerializable {}
}
