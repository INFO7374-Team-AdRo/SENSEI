package edu.neu.safety.agents.reasoning;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.EscalationTier;
import edu.neu.safety.model.FusedSnapshot;
import edu.neu.safety.model.IncidentReport;

import java.util.List;

/**
 * Message contract for {@link LLMReasoningAgent}. Four capabilities, each
 * with its own command + reply pair, plus a status probe.
 */
public interface ReasoningProtocol {

    /** Parent marker for everything the reasoning agent accepts. */
    sealed interface Command extends CborSerializable permits
        AnalyzeIncident, GenerateReport, NLQuery, GetStatus {}

    /**
     * Capability 1+2: analyze a live hazard. The reply carries a narrative
     * analysis, a concrete recommendation, and a suggested escalation tier.
     * The retrieval agent's output is passed in through {@code pastIncidents}
     * so the LLM can reason with RAG context.
     */
    record AnalyzeIncident(
        ClassificationResult classification,
        FusedSnapshot sensorData,
        List<IncidentReport.PastIncident> pastIncidents,
        ActorRef<ReasoningResult> replyTo
    ) implements Command {}

    /**
     * Capability 3: produce a long-form post-incident report suitable for
     * OSHA / MSHA filing. The incident object should already have analysis
     * and escalation fields filled in.
     */
    record GenerateReport(
        String incidentId,
        IncidentReport incident,
        ActorRef<GeneratedReport> replyTo
    ) implements Command {}

    /**
     * Capability 4: free-form operator query from the dashboard.
     *
     * <p>{@code recentIncidents} is the chronological list (newest first)
     * used to ground the prompt in real system state so the LLM doesn't
     * emit placeholder text for status/history questions. The HTTP layer
     * fetches that list from the orchestrator before forwarding the query.
     */
    record NLQuery(
        String query,
        String conversationId,
        List<IncidentReport> recentIncidents,
        ActorRef<NLResponse> replyTo
    ) implements Command {}

    /** Ask-pattern status query used by /api-status. */
    record GetStatus(ActorRef<ReasoningStatus> replyTo) implements Command {}

    /** Reply to {@link AnalyzeIncident}. */
    record ReasoningResult(
        String analysis,
        String recommendation,
        EscalationTier suggestedTier,
        long timestampMs
    ) implements CborSerializable {}

    /** Reply to {@link GenerateReport} — structured sections plus the full text. */
    record GeneratedReport(
        String incidentId,
        String summary,
        String timeline,
        String rootCause,
        String actionsTaken,
        String preventionRecommendation,
        String fullReport
    ) implements CborSerializable {}

    /** Reply to {@link NLQuery}. */
    record NLResponse(
        String answer,
        String queryType,
        List<String> sourcesUsed
    ) implements CborSerializable {}

    /** Reply to {@link GetStatus}. */
    record ReasoningStatus(
        int analysesCompleted,
        int llmCalls,
        int fallbacksUsed,
        int reportsGenerated,
        int nlQueriesAnswered,
        String lastAnalysis
    ) implements CborSerializable {}
}
