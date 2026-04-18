package edu.neu.safety.agents.reasoning;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.safety.external.MistralClient;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.EscalationTier;
import edu.neu.safety.model.FusedSnapshot;
import edu.neu.safety.model.IncidentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "brain" of the pipeline. Wraps Mistral and exposes four capabilities:
 *
 * <ol>
 *   <li>{@link ReasoningProtocol.AnalyzeIncident} — real-time hazard
 *       analysis over the current classification + sensor readings +
 *       retrieved past incidents (RAG).</li>
 *   <li>Root-cause hypothesis — bundled into the analysis response, not a
 *       separate message.</li>
 *   <li>{@link ReasoningProtocol.GenerateReport} — long-form OSHA/MSHA-style
 *       post-incident write-up using the {@code chatFreeform} endpoint.</li>
 *   <li>{@link ReasoningProtocol.NLQuery} — natural-language operator chat
 *       from the dashboard. Prompt is grounded on the orchestrator's recent
 *       incidents buffer so the LLM doesn't hallucinate.</li>
 * </ol>
 *
 * <p>Every capability has a deterministic rule-based fallback that kicks in
 * when the Mistral API key is missing or a call fails. That way the
 * pipeline keeps producing escalation decisions even during a full Mistral
 * outage — escalation doesn't care where the analysis came from, only that
 * it arrived.
 */
public class LLMReasoningAgent extends AbstractBehavior<ReasoningProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(LLMReasoningAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String ANALYSIS_SYSTEM_PROMPT = """
        You are an industrial safety analyst for a real-time hazard monitoring system.
        Given sensor readings, a gas classification result, and similar past incidents,
        provide a structured safety analysis.

        Respond ONLY with valid JSON in this exact format:
        {
            "analysis": "Brief analysis of the current situation (2-3 sentences)",
            "recommendation": "Specific recommended action (1-2 sentences)",
            "tier": "NONE|T1_LOG|T2_ALERT|T3_SHUTDOWN"
        }

        Escalation tiers:
        - NONE: No hazard, normal operations
        - T1_LOG: Low confidence hazard (60-75%), log for review
        - T2_ALERT: Medium confidence hazard (75-90%), alert operator immediately
        - T3_SHUTDOWN: High confidence hazard (>90%), initiate emergency shutdown
        """;

    private static final String REPORT_SYSTEM_PROMPT = """
        You are writing a formal post-incident safety report for an industrial facility.
        Write a professional, structured report that includes:
        - Executive summary (2-3 sentences)
        - Timeline of events
        - Root cause determination
        - Actions taken by the automated system
        - Recommendations for prevention
        The report should be suitable for OSHA/MSHA compliance documentation.
        """;

    private static final String NL_SYSTEM_PROMPT = """
        You are a safety monitoring assistant for an industrial gas/smoke detection system.
        You will receive a LIVE SYSTEM STATE block followed by the operator's question.

        RULES:
        - Answer ONLY from the LIVE SYSTEM STATE block. Do not invent incident IDs, times,
          sensor values, or counts. If the block is empty, say "no incidents observed yet".
        - Never emit placeholder text like "[Incident 1 details]".
        - Keep answers short (1–3 sentences).
        - Classify the query as one of: status, history, explanation, unknown.

        Respond ONLY with valid JSON:
        {
          "answer": "your response grounded in the LIVE SYSTEM STATE",
          "queryType": "status|history|explanation|unknown",
          "sourcesUsed": ["sensor_data", "incident_log", "llm_knowledge"]
        }
        """;

    private final MistralClient mistralClient;
    private final boolean llmAvailable;
    private int analysesCompleted = 0;
    private int llmCalls = 0;
    private int fallbacksUsed = 0;
    private int reportsGenerated = 0;
    private int nlQueriesAnswered = 0;
    private String lastAnalysis = "";

    /**
     * Factory. Backoff restart so 5xx storms from Mistral don't take down
     * the agent — it'll just sleep a bit before retrying.
     */
    public static Behavior<ReasoningProtocol.Command> create(
            String apiKey, String baseUrl, String model) {
        return Behaviors.supervise(
            Behaviors.<ReasoningProtocol.Command>setup(context ->
                new LLMReasoningAgent(context, apiKey, baseUrl, model))
        ).onFailure(SupervisorStrategy.restartWithBackoff(
            Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2));
    }

    private LLMReasoningAgent(ActorContext<ReasoningProtocol.Command> context,
                              String apiKey, String baseUrl, String model) {
        super(context);
        boolean available = apiKey != null && !apiKey.isEmpty();
        this.mistralClient = available ? new MistralClient(apiKey, baseUrl, model) : null;
        this.llmAvailable = available;
        if (llmAvailable) {
            log.info("LLMReasoningAgent: Mistral configured (model={})", model);
        } else {
            log.warn("LLMReasoningAgent: no API key — using rule-based fallback");
        }
    }

    /** Message dispatch. */
    @Override
    public Receive<ReasoningProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ReasoningProtocol.AnalyzeIncident.class, this::onAnalyzeIncident)
            .onMessage(ReasoningProtocol.GenerateReport.class, this::onGenerateReport)
            .onMessage(ReasoningProtocol.NLQuery.class, this::onNLQuery)
            .onMessage(ReasoningProtocol.GetStatus.class, this::onGetStatus)
            .build();
    }

    /**
     * Capability 1+2 handler. Skips the LLM for NO_GAS snapshots since
     * there's nothing to reason about — we still return a rule-based
     * result so the orchestrator has something to feed into escalation.
     */
    private Behavior<ReasoningProtocol.Command> onAnalyzeIncident(ReasoningProtocol.AnalyzeIncident cmd) {
        ReasoningProtocol.ReasoningResult result;
        if (llmAvailable && !cmd.classification().label().equals("NO_GAS")) {
            try {
                String userPrompt = buildAnalysisPrompt(cmd);
                String response = mistralClient.chat(ANALYSIS_SYSTEM_PROMPT, userPrompt);
                result = parseAnalysisResponse(response);
                llmCalls++;
                log.info("LLM analysis for {}: tier={}",
                    cmd.classification().label(), result.suggestedTier());
            } catch (Exception e) {
                log.warn("LLM analysis failed, falling back to rules: {}", e.getMessage());
                result = ruleBasedReasoning(cmd);
                fallbacksUsed++;
            }
        } else {
            result = ruleBasedReasoning(cmd);
            if (!cmd.classification().label().equals("NO_GAS")) fallbacksUsed++;
        }

        analysesCompleted++;
        lastAnalysis = result.analysis();
        cmd.replyTo().tell(result);
        return this;
    }

    /**
     * Capability 3 handler. Gathers the full context of an incident into a
     * prompt block and asks the LLM for a longer OSHA-style write-up, then
     * splits the response into named sections for the dashboard. Falls
     * back to a simple templated report if the API is unavailable.
     */
    private Behavior<ReasoningProtocol.Command> onGenerateReport(ReasoningProtocol.GenerateReport cmd) {
        IncidentReport inc = cmd.incident();
        StringBuilder context = new StringBuilder();
        context.append("INCIDENT ID: ").append(inc.id()).append("\n");
        context.append("Classification: ").append(inc.classificationLabel())
            .append(" (").append(String.format("%.0f%%", inc.classificationConfidence() * 100)).append(")\n");
        context.append("Tier: ").append(inc.escalationTier()).append("\n");
        context.append("Action taken: ").append(inc.escalationAction()).append("\n");
        context.append("Sensor readings: ").append(inc.sensorValues()).append("\n");
        context.append("Thermal max: ").append(inc.maxTemp()).append("C\n");
        context.append("Pre-LLM analysis: ").append(inc.llmAnalysis()).append("\n");
        context.append("Recommendation: ").append(inc.llmRecommendation()).append("\n");
        if (!inc.similarIncidents().isEmpty()) {
            context.append("\nSIMILAR PAST INCIDENTS:\n");
            for (IncidentReport.PastIncident pi : inc.similarIncidents()) {
                context.append("- ").append(pi.description())
                    .append(" → ").append(pi.resolution()).append("\n");
            }
        }

        String fullReport;
        try {
            fullReport = llmAvailable
                ? mistralClient.chatFreeform(REPORT_SYSTEM_PROMPT, context.toString())
                : buildFallbackReport(inc, context.toString());
        } catch (Exception e) {
            log.warn("Report generation failed, using fallback: {}", e.getMessage());
            fullReport = buildFallbackReport(inc, context.toString());
        }

        ReasoningProtocol.GeneratedReport report = new ReasoningProtocol.GeneratedReport(
            inc.id(),
            extractSection(fullReport, "summary",         inc.llmAnalysis()),
            extractSection(fullReport, "timeline",        "Timeline data unavailable"),
            extractSection(fullReport, "root cause",      inc.llmAnalysis()),
            extractSection(fullReport, "actions",         inc.escalationAction()),
            extractSection(fullReport, "recommendation",  inc.llmRecommendation()),
            fullReport
        );
        reportsGenerated++;
        cmd.replyTo().tell(report);
        return this;
    }

    /**
     * Capability 4 handler. The operator's question is prefixed with a
     * LIVE SYSTEM STATE block built from recent incidents so Mistral is
     * grounded on real data. Without this grounding, questions like "what
     * was the last hazard?" end up as hallucinated placeholder text.
     */
    private Behavior<ReasoningProtocol.Command> onNLQuery(ReasoningProtocol.NLQuery cmd) {
        if (!llmAvailable) {
            cmd.replyTo().tell(new ReasoningProtocol.NLResponse(
                "LLM is not configured. Set MISTRAL_API_KEY to enable natural-language chat.",
                "unknown", List.of()));
            return this;
        }
        try {
            String grounded = buildGroundedNLPrompt(cmd);
            String response = mistralClient.chat(NL_SYSTEM_PROMPT, grounded);
            String cleaned = stripCodeFences(response);
            String answer = cleaned;
            String queryType = "explanation";
            List<String> sources = new ArrayList<>();
            try {
                JsonNode root = mapper.readTree(cleaned);
                answer    = root.path("answer").asText(cleaned);
                queryType = root.path("queryType").asText("explanation");
                JsonNode srcNode = root.path("sourcesUsed");
                if (srcNode.isArray()) srcNode.forEach(n -> sources.add(n.asText()));
            } catch (Exception ignored) {
                // Mistral returned plain text — use as-is.
            }
            nlQueriesAnswered++;
            cmd.replyTo().tell(new ReasoningProtocol.NLResponse(answer, queryType, sources));
        } catch (Exception e) {
            log.error("NL query failed: {}", e.getMessage());
            cmd.replyTo().tell(new ReasoningProtocol.NLResponse(
                "Query processing failed: " + e.getMessage(), "unknown", List.of()));
        }
        return this;
    }

    /** Dashboard probe — counters for LLM calls, fallbacks and queries answered. */
    private Behavior<ReasoningProtocol.Command> onGetStatus(ReasoningProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new ReasoningProtocol.ReasoningStatus(
            analysesCompleted, llmCalls, fallbacksUsed, reportsGenerated, nlQueriesAnswered, lastAnalysis
        ));
        return this;
    }

    // -------- helpers --------

    /**
     * Build the "LIVE SYSTEM STATE" header that prefixes every NL query.
     * Lists the most recent incident with its sensor map, a rolling window
     * of the 10 most recent incidents, and tier totals. Everything the LLM
     * might want to answer "what's going on?" without guessing.
     */
    private String buildGroundedNLPrompt(ReasoningProtocol.NLQuery cmd) {
        List<IncidentReport> incidents = cmd.recentIncidents() == null
            ? List.of() : cmd.recentIncidents();
        StringBuilder sb = new StringBuilder();
        sb.append("LIVE SYSTEM STATE\n");
        sb.append("Total incidents observed: ").append(incidents.size()).append("\n");

        if (!incidents.isEmpty()) {
            IncidentReport latest = incidents.get(0);
            sb.append("\nCURRENT (most recent incident):\n");
            sb.append("  id=").append(latest.id())
              .append(" ts=").append(java.time.Instant.ofEpochMilli(latest.timestampMs()))
              .append(" class=").append(latest.classificationLabel())
              .append(" conf=").append(String.format("%.0f%%", latest.classificationConfidence() * 100))
              .append(" tier=").append(latest.escalationTier()).append("\n");
            if (latest.sensorValues() != null) {
                sb.append("  sensors:");
                latest.sensorValues().forEach((k, v) ->
                    sb.append(" ").append(k).append("=").append(String.format("%.0f", v)));
                sb.append(" maxTempC=").append(String.format("%.1f", latest.maxTemp())).append("\n");
            }
            if (latest.escalationAction() != null && !latest.escalationAction().isBlank()) {
                sb.append("  action=").append(latest.escalationAction()).append("\n");
            }

            int windowSize = Math.min(10, incidents.size());
            sb.append("\nRECENT INCIDENTS (newest first, ").append(windowSize).append(" of ")
              .append(incidents.size()).append("):\n");
            for (int i = 0; i < windowSize; i++) {
                IncidentReport r = incidents.get(i);
                sb.append("  ").append(i + 1).append(". ")
                  .append(java.time.Instant.ofEpochMilli(r.timestampMs()))
                  .append(" · ").append(r.classificationLabel())
                  .append(" (").append(String.format("%.0f%%", r.classificationConfidence() * 100)).append(")")
                  .append(" · tier=").append(r.escalationTier())
                  .append(" · id=").append(r.id()).append("\n");
            }

            long shutdowns = incidents.stream()
                .filter(r -> r.escalationTier() == EscalationTier.T3_SHUTDOWN).count();
            long alerts = incidents.stream()
                .filter(r -> r.escalationTier() == EscalationTier.T2_ALERT).count();
            long notifies = incidents.stream()
                .filter(r -> r.escalationTier() == EscalationTier.T1_LOG).count();
            sb.append("\nTOTALS: T3_SHUTDOWN=").append(shutdowns)
              .append(" T2_ALERT=").append(alerts)
              .append(" T1_LOG=").append(notifies).append("\n");
        }

        sb.append("\nOPERATOR QUESTION: ").append(cmd.query()).append("\n");
        return sb.toString();
    }

    /**
     * Format the incident context for the analysis prompt — classification,
     * sensor readings, thermal stats, and any similar past incidents pulled
     * by the retrieval agent.
     */
    private String buildAnalysisPrompt(ReasoningProtocol.AnalyzeIncident cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLASSIFICATION: ").append(cmd.classification().label())
            .append(" (confidence: ").append(String.format("%.2f", cmd.classification().confidence()))
            .append(")\n\n");
        sb.append("SENSOR READINGS:\n");
        for (Map.Entry<String, Double> entry : cmd.sensorData().sensorValues().entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ")
                .append(String.format("%.2f", entry.getValue())).append(" ppm\n");
        }
        sb.append("  Thermal Max: ").append(String.format("%.1f", cmd.sensorData().maxTemp())).append("C\n");
        sb.append("  Thermal Avg: ").append(String.format("%.1f", cmd.sensorData().avgTemp())).append("C\n");
        sb.append("  Thermal Anomaly: ").append(cmd.sensorData().thermalAnomaly()).append("\n\n");
        if (!cmd.pastIncidents().isEmpty()) {
            sb.append("SIMILAR PAST INCIDENTS:\n");
            for (int i = 0; i < cmd.pastIncidents().size(); i++) {
                IncidentReport.PastIncident incident = cmd.pastIncidents().get(i);
                sb.append("  ").append(i + 1).append(". [Score: ")
                    .append(String.format("%.2f", incident.similarityScore())).append("] ")
                    .append(incident.description()).append("\n")
                    .append("     Resolution: ").append(incident.resolution()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Parse the JSON blob Mistral returns for analysis. If Mistral slips
     * into plain prose (happens occasionally when the model has a bad day)
     * we fall back to using the whole response as the analysis text and
     * tag the incident T1_LOG.
     */
    private ReasoningProtocol.ReasoningResult parseAnalysisResponse(String response) {
        try {
            String cleaned = stripCodeFences(response);
            JsonNode root = mapper.readTree(cleaned);
            String analysis = root.path("analysis").asText("Analysis unavailable");
            String recommendation = root.path("recommendation").asText("Monitor situation");
            String tierStr = root.path("tier").asText("T1_LOG");
            EscalationTier tier = switch (tierStr.toUpperCase()) {
                case "NONE" -> EscalationTier.NONE;
                case "T1_LOG" -> EscalationTier.T1_LOG;
                case "T2_ALERT" -> EscalationTier.T2_ALERT;
                case "T3_SHUTDOWN" -> EscalationTier.T3_SHUTDOWN;
                default -> EscalationTier.T1_LOG;
            };
            return new ReasoningProtocol.ReasoningResult(
                analysis, recommendation, tier, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON response: {}", e.getMessage());
            return new ReasoningProtocol.ReasoningResult(
                response, "Review LLM output manually",
                EscalationTier.T1_LOG, System.currentTimeMillis());
        }
    }

    /** Mistral sometimes wraps JSON in ```json fences. Strip them before parsing. */
    private String stripCodeFences(String raw) {
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).strip();
        }
        return cleaned;
    }

    /** Templated report used when the LLM is off or errored — still has all the key fields. */
    private String buildFallbackReport(IncidentReport inc, String context) {
        return String.format("""
            INCIDENT REPORT (auto-generated, LLM unavailable)
            ---------------------------------------------------
            Incident: %s
            Class:    %s (%.0f%%)
            Tier:     %s
            Action:   %s

            Pre-LLM analysis: %s
            Recommendation:   %s

            Raw context:
            %s
            """,
            inc.id(), inc.classificationLabel(), inc.classificationConfidence() * 100,
            inc.escalationTier(), inc.escalationAction(),
            inc.llmAnalysis(), inc.llmRecommendation(), context);
    }

    /**
     * Cheap substring-based section extractor. Does a case-insensitive
     * find for a header keyword and returns the next ~500 characters of
     * text. Good enough for the dashboard's structured report panel.
     */
    private String extractSection(String report, String sectionName, String fallback) {
        if (report == null) return fallback;
        String lower = report.toLowerCase();
        int idx = lower.indexOf(sectionName.toLowerCase());
        if (idx == -1) return fallback;
        int start = report.indexOf('\n', idx);
        if (start == -1) return fallback;
        int end = report.indexOf('\n', start + 100);
        if (end == -1) end = Math.min(start + 500, report.length());
        return report.substring(start, end).trim();
    }

    /**
     * Deterministic fallback used when the LLM is unavailable. The rules
     * mirror what the LLM tends to say for obvious cases, and are tuned
     * against the four-class dataset so smoke / perfume / combined get
     * triaged roughly correctly.
     */
    private ReasoningProtocol.ReasoningResult ruleBasedReasoning(ReasoningProtocol.AnalyzeIncident cmd) {
        ClassificationResult classification = cmd.classification();
        FusedSnapshot data = cmd.sensorData();
        String analysis;
        String recommendation;
        EscalationTier tier;
        switch (classification.label()) {
            case "SMOKE" -> {
                if (classification.confidence() > 0.9) {
                    analysis = String.format("High-confidence smoke detection. MQ2=%.0f, MQ7=%.0f ppm. " +
                            "Thermal max=%.1fC. Cross-modal correlation confirms hazard.",
                        data.sensorValues().getOrDefault("MQ2", 0.0),
                        data.sensorValues().getOrDefault("MQ7", 0.0),
                        data.maxTemp());
                    recommendation = "Initiate emergency ventilation and prepare for evacuation. Deploy fire safety team.";
                    tier = EscalationTier.T3_SHUTDOWN;
                } else if (classification.confidence() > 0.75) {
                    analysis = String.format("Moderate smoke indicators. Confidence %.0f%%. " +
                            "Elevated readings on combustion-related sensors.",
                        classification.confidence() * 100);
                    recommendation = "Alert on-duty operator. Increase monitoring frequency.";
                    tier = EscalationTier.T2_ALERT;
                } else {
                    analysis = "Low-confidence smoke signal. May be environmental noise or sensor drift.";
                    recommendation = "Log event for review. Continue monitoring.";
                    tier = EscalationTier.T1_LOG;
                }
            }
            case "PERFUME" -> {
                analysis = String.format("VOC/perfume-type substance detected. MQ3=%.0f, MQ135=%.0f ppm. " +
                        "Non-toxic but may indicate chemical presence.",
                    data.sensorValues().getOrDefault("MQ3", 0.0),
                    data.sensorValues().getOrDefault("MQ135", 0.0));
                recommendation = "Identify source. Check for cleaning agents or chemical spills.";
                tier = classification.confidence() > 0.85 ? EscalationTier.T2_ALERT : EscalationTier.T1_LOG;
            }
            case "COMBINED" -> {
                analysis = String.format("CRITICAL: Combined gas and smoke detection. Multiple sensor types " +
                        "elevated simultaneously. Thermal=%.1fC. This pattern matches serious hazard events.",
                    data.maxTemp());
                recommendation = "IMMEDIATE: Initiate emergency shutdown. Evacuate all personnel. Deploy hazmat team.";
                tier = EscalationTier.T3_SHUTDOWN;
            }
            default -> {
                analysis = "Normal operations. All sensor readings within baseline parameters.";
                recommendation = "No action required. Continue standard monitoring.";
                tier = EscalationTier.NONE;
            }
        }
        return new ReasoningProtocol.ReasoningResult(
            analysis, recommendation, tier, System.currentTimeMillis());
    }
}
