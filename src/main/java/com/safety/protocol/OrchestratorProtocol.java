
package com.safety.protocol;

import akka.actor.typed.ActorRef;
import java.util.List;

public class OrchestratorProtocol {

    public record IncidentFinalized(
        String incidentId,
        EscalationProtocol.EscalationDecision decision,
        ClassificationProtocol.ClassificationResult classification,
        LLMProtocol.ReasoningResult reasoning
    ) implements CborSerializable {}

    public record GetSystemStatus(
        ActorRef<SystemStatus> replyTo
    ) implements CborSerializable {}

    public record SystemStatus(
        int activeSensorShards,
        int totalEventsProcessed,
        int openIncidents,
        List<EscalationProtocol.EscalationDecision> recentEscalations
    ) implements CborSerializable {}
}