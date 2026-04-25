package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.safety.clients.MistralClient;
import com.safety.clients.MongoDBService;
import com.safety.protocol.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LLMReasoningAgent extends AbstractBehavior<LLMProtocol.Command> {

    private final MistralClient mistral;
    private final MongoDBService mongo;
    private final akka.actor.typed.ActorRef<RetrievalAgent.Command> retrievalAgent;
    private final Gson gson = new Gson();
    // Max turns to load from MongoDB for context window
    private static final int MAX_HISTORY_TURNS = 20;

    /**
     * Create without retrieval (used by tests or legacy wiring).
     * Chat queries will run without RAG context.
     */
    public static Behavior<LLMProtocol.Command> create(MistralClient mistralClient, MongoDBService mongo) {
        return Behaviors.setup(ctx -> new LLMReasoningAgent(ctx, mistralClient, mongo, null));
    }

    // with retrieval wired — chat queries search Qdrant before hitting Mistral
    public static Behavior<LLMProtocol.Command> create(
            MistralClient mistralClient,
            MongoDBService mongo,
            akka.actor.typed.ActorRef<RetrievalAgent.Command> retrievalAgent) {
        return Behaviors.setup(ctx -> new LLMReasoningAgent(ctx, mistralClient, mongo, retrievalAgent));
    }

    private LLMReasoningAgent(ActorContext<LLMProtocol.Command> context,
                               MistralClient mistral,
                               MongoDBService mongo,
                               akka.actor.typed.ActorRef<RetrievalAgent.Command> retrievalAgent) {
        super(context);
        this.mistral = mistral;
        this.mongo = mongo;
        this.retrievalAgent = retrievalAgent;
        context.getLog().info("LLMReasoningAgent started (mongoAvailable={} rag={})",
            mongo != null && mongo.isAvailable(), retrievalAgent != null);
    }

    /**
     * Internal command: Qdrant responded with similar incidents for a chat query.
     * Carries the original NLQuery so the second phase can complete the Mistral call.
     */
    private record ChatRagResult(
        LLMProtocol.NLQuery original,
        List<RetrievalProtocol.PastIncident> incidents
    ) implements LLMProtocol.Command {}

    @Override
    public Receive<LLMProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(LLMProtocol.ReasoningRequest.class, this::onReasoningRequest)
            .onMessage(LLMProtocol.GenerateReport.class, this::onGenerateReport)
            .onMessage(LLMProtocol.NLQuery.class, this::onNLQuery)
            .onMessage(ChatRagResult.class, this::onChatRagResult)
            .onMessage(LLMProtocol.ToolResponse.class, this::onToolResponse)
            .build();
    }

    // ---- Capability 1 + 2: Multi-step reasoning + Root cause analysis ----
    private Behavior<LLMProtocol.Command> onReasoningRequest(LLMProtocol.ReasoningRequest request) {
        var classification = request.context().classification();

        // Build sensor context only — RAG similar incidents removed from reasoning input
        // to prevent identical recommendations anchored to past resolutions.
        String sensorContext = buildSensorContext(classification);

        String systemPrompt = """
            You are an industrial safety AI analyzing real-time multimodal sensor data.

            Gas Sensors (ADC scale — LOWER value = MORE gas present):
            MQ2 (flammable gas/smoke, breach threshold ~690), MQ3 (alcohol vapour, breach ~490),
            MQ5 (LPG/natural gas, breach ~400), MQ6 (butane/propane, breach ~390),
            MQ7 (carbon monoxide, breach ~560), MQ8 (hydrogen, breach ~590),
            MQ135 (air quality/ammonia/benzene, breach ~440).
            Thermal camera: detects heat anomalies; anomalyDetected=true means elevated IR signature.

            Your job: given the exact sensor readings below, produce a precise, specific incident analysis.

            Rules:
            - Use the ACTUAL sensor names and ADC values from the input — never invent readings.
            - Tailor the recommendation to the SPECIFIC gas combination (e.g. CO alone → ventilate and check combustion appliances; LPG + flammable → shut off gas supply and evacuate; hydrogen alone → no ignition sources and vent upward).
            - Vary the explanation based on which sensors triggered and how far below threshold they are.
            - Severity: "low" (single sensor, mild breach), "medium" (2-3 sensors or moderate breach), "high" (4+ sensors or deep breach), "critical" (all sensors + thermal).
            - Do NOT use generic phrases like "investigate the source" or "evacuate the area" as the entire recommendation — be operationally specific.

            Respond with ONLY a valid JSON object — no markdown, no text outside the JSON:
            {
              "hazardType": "<specific gas or combination, e.g. LPG+Smoke>",
              "severity": "<low|medium|high|critical>",
              "causalChain": "<1-2 sentences: which sensors triggered, what readings, what the combination implies>",
              "evidenceSteps": ["<step 1>", "<step 2>", "<step 3>"],
              "recommendation": "<2-3 specific operational actions tailored to this gas type and severity>",
              "explanation": "<2-3 sentence plain-English summary of what happened and why it matters, NO JSON, NO code>"
            }
            """;

        String userMessage = String.format("""
            CURRENT EVENT:
            Classification: %s (confidence: %.2f)
            %s

            Based on the exact sensor data above, produce your analysis now.
            """,
            classification.hazardClass(),
            classification.confidence(),
            sensorContext
        );

        String response;
        try {
            response = mistral.chat(systemPrompt, userMessage);
        } catch (java.io.IOException e) {
            // Infrastructure failure — propagate so ReasoningSupervisor can restart with backoff
            throw new RuntimeException("Mistral API call failed in reasoning request", e);
        }

        // Clean up response — strip markdown code fences if present
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // Try to parse as JSON first, fall back to extracting from text
        String hazardType = classification.hazardClass();
        String severity = "medium";
        String causalChain = "Analysis pending";
        List<String> evidenceSteps = new ArrayList<>();
        String recommendation = "Investigate manually";
        // Default to null so we can detect if the field was actually populated from JSON
        String explanation = null;

        try {
            // Try JSON parse
            JsonObject parsed = gson.fromJson(cleaned, JsonObject.class);
            if (parsed.has("hazardType"))    hazardType     = parsed.get("hazardType").getAsString();
            if (parsed.has("severity"))      severity       = parsed.get("severity").getAsString();
            if (parsed.has("causalChain"))   causalChain    = parsed.get("causalChain").getAsString();
            if (parsed.has("recommendation")) recommendation = parsed.get("recommendation").getAsString();
            if (parsed.has("explanation"))   explanation    = parsed.get("explanation").getAsString();
            if (parsed.has("evidenceSteps")) {
                parsed.getAsJsonArray("evidenceSteps")
                    .forEach(e -> evidenceSteps.add(e.getAsString()));
            }
        } catch (Exception jsonErr) {
            // Not JSON — extract what we can from plain text
            getContext().getLog().debug("LLM returned text, not JSON — using as explanation");

            // Simple keyword extraction for severity
            String lower = response.toLowerCase();
            if (lower.contains("critical") || lower.contains("immediate") || lower.contains("evacuate")) {
                severity = "critical";
            } else if (lower.contains("high") || lower.contains("danger") || lower.contains("serious")) {
                severity = "high";
            } else if (lower.contains("low") || lower.contains("minor") || lower.contains("safe")) {
                severity = "low";
            }
        }

        // If explanation was never set from parsed JSON, build a clean sentence from other fields
        // rather than falling back to the raw LLM response (which may be a JSON blob).
        if (explanation == null || explanation.isBlank()) {
            explanation = String.format(
                "%s event detected (%s severity). %s. Immediate action: %s",
                hazardType, severity,
                causalChain.endsWith(".") ? causalChain : causalChain + ".",
                recommendation
            );
        }

        var result = new LLMProtocol.ReasoningResult(
            hazardType, severity, causalChain, evidenceSteps,
            recommendation, explanation,
            Math.max(1, evidenceSteps.size()), Instant.now()
        );

        getContext().getLog().info("LLM reasoning complete: {} severity={} steps={}",
            result.hazardType(), result.severity(), result.reasoningSteps());

        request.replyTo().tell(result);

        return this;
    }

    // ---- Capability 3: Post-incident report generation ----
    private Behavior<LLMProtocol.Command> onGenerateReport(LLMProtocol.GenerateReport request) {
        String systemPrompt = """
            You are writing a formal post-incident safety report for an industrial facility.
            Write a professional, structured report that includes:
            - Executive summary (2-3 sentences)
            - Timeline of events
            - Root cause determination
            - Actions taken by the automated system
            - Recommendations for prevention
            
            The report should be suitable for OSHA/MSHA compliance documentation.
            """;

        StringBuilder context = new StringBuilder();
        context.append("INCIDENT ID: ").append(request.incidentId()).append("\n\n");

        context.append("EVENT HISTORY:\n");
        for (var event : request.eventHistory()) {
            context.append(String.format("- %s: %s (severity: %s) — %s\n",
                event.timestamp(), event.hazardType(), event.severity(), event.causalChain()));
        }

        context.append("\nESCALATION HISTORY:\n");
        for (var esc : request.escalationHistory()) {
            context.append(String.format("- %s: %s — %s\n",
                esc.timestamp(), esc.tier(), esc.action()));
        }

        String report;
        try {
            report = mistral.chatFreeform(systemPrompt, context.toString());
        } catch (java.io.IOException e) {
            // Infrastructure failure — propagate so ReasoningSupervisor can restart with backoff
            throw new RuntimeException("Mistral API call failed in report generation", e);
        }

        var result = new LLMProtocol.IncidentReport(
            request.incidentId(),
            extractSection(report, "summary", report.substring(0, Math.min(200, report.length()))),
            extractSection(report, "timeline", "See full report"),
            extractSection(report, "root cause", "See full report"),
            extractSection(report, "actions", "See full report"),
            extractSection(report, "recommendation", "See full report"),
            report
        );

        getContext().getLog().info("Generated incident report for {}", request.incidentId());
        request.replyTo().tell(result);

        return this;
    }

    // ---- Capability 4: Natural language querying (Phase 1 — kick off Qdrant search) ----
    private Behavior<LLMProtocol.Command> onNLQuery(LLMProtocol.NLQuery request) {
        if (retrievalAgent != null) {
            // Phase 1: embed the user's query and search Qdrant for similar past incidents.
            // The RAG results come back as ChatRagResult and Phase 2 calls Mistral with them.
            akka.actor.typed.ActorRef<RetrievalProtocol.TextQueryResult> ragAdapter =
                getContext().messageAdapter(
                    RetrievalProtocol.TextQueryResult.class,
                    result -> new ChatRagResult(request, result.incidents())
                );
            retrievalAgent.tell(new RetrievalAgent.TextQuery(
                new RetrievalProtocol.TextQueryRequest(request.query(), 3, ragAdapter)
            ));
        } else {
            // No retrieval wired — skip directly to Mistral with empty incident list
            getContext().getSelf().tell(new ChatRagResult(request, List.of()));
        }
        return this;
    }

    // ---- Capability 4: Natural language querying (Phase 2 — call Mistral with RAG context) ----
    private Behavior<LLMProtocol.Command> onChatRagResult(ChatRagResult msg) {
        var request   = msg.original();
        var incidents = msg.incidents();

        String liveCtx = (request.liveContext() != null && !request.liveContext().isBlank())
            ? request.liveContext()
            : "No live data available.";

        // Build RAG section — only include if Qdrant returned results above minimum similarity
        String ragSection = buildRagContext(incidents);

        String systemPrompt = "You are a safety monitoring assistant for an industrial gas detection system.\n"
            + "Answer ONLY based on data provided in the LIVE SYSTEM STATE and SIMILAR PAST INCIDENTS below.\n"
            + "Do NOT invent zones, areas, personnel names, shift times, or readings not present in the provided data.\n"
            + "If you do not have enough data to answer, say so clearly — do not guess or make up context.\n"
            + "Maintain context from prior messages in this conversation.\n\n"
            + "LIVE SYSTEM STATE:\n" + liveCtx + "\n\n"
            + ragSection
            + "Categorize the query as one of: status, history, explanation, unknown.\n\n"
            + "Respond in JSON:\n"
            + "{ \"answer\": \"your response\", \"queryType\": \"status|history|explanation|unknown\", "
            + "\"sourcesUsed\": [\"sensor_data\", \"incident_log\", \"llm_knowledge\"] }";

        List<Map<String, String>> history = (mongo != null)
            ? mongo.loadChatHistory(request.conversationId(), MAX_HISTORY_TURNS * 2)
            : new ArrayList<>();

        try {
            String response = mistral.chatWithHistory(systemPrompt, history, request.query());

            String cleaned = response.trim();
            if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
            else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();

            String answer = cleaned;
            String queryType = "explanation";
            List<String> sources = new ArrayList<>();

            try {
                JsonObject parsed = gson.fromJson(cleaned, JsonObject.class);
                if (parsed.has("answer"))     answer    = parsed.get("answer").getAsString();
                if (parsed.has("queryType"))  queryType = parsed.get("queryType").getAsString();
                if (parsed.has("sourcesUsed")) {
                    parsed.getAsJsonArray("sourcesUsed")
                        .forEach(e -> sources.add(e.getAsString()));
                }
            } catch (Exception jsonErr) {
                getContext().getLog().debug("NL query: Mistral returned plain text, using directly");
            }

            // Add "rag" to sources if Qdrant returned relevant incidents
            if (!incidents.isEmpty() && !sources.contains("rag")) {
                sources.add("rag");
            }

            if (mongo != null) {
                mongo.saveChatTurn(request.conversationId(), "user",      request.query());
                mongo.saveChatTurn(request.conversationId(), "assistant", answer);
            }

            getContext().getLog().info("NL query answered — conv={} type={} ragIncidents={}",
                request.conversationId(), queryType, incidents.size());
            request.replyTo().tell(new LLMProtocol.NLResponse(answer, queryType, sources));

        } catch (Exception e) {
            getContext().getLog().error("NL query failed: {}", e.getMessage());
            request.replyTo().tell(new LLMProtocol.NLResponse(
                "Query processing failed: " + e.getMessage(), "unknown", List.of()
            ));
        }

        return this;
    }

    /** Format Qdrant results as a readable context block for the LLM system prompt. */
    private String buildRagContext(List<RetrievalProtocol.PastIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) return "";
        // Only include incidents with meaningful similarity (score > 0.5)
        List<RetrievalProtocol.PastIncident> relevant = incidents.stream()
            .filter(i -> i.similarityScore() > 0.5)
            .collect(Collectors.toList());
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("SIMILAR PAST INCIDENTS (from incident history):\n");
        for (var inc : relevant) {
            sb.append(String.format("- [%.0f%% match] %s: %s → Action taken: %s\n",
                inc.similarityScore() * 100,
                inc.hazardClass(),
                inc.rootCause(),
                inc.resolution()));
        }
        sb.append("\n");
        return sb.toString();
    }

    // ---- Tool response handler for multi-step reasoning ----
    private Behavior<LLMProtocol.Command> onToolResponse(LLMProtocol.ToolResponse response) {
        getContext().getLog().debug("Received tool response: {} -> {}",
            response.toolName(), response.resultJson().substring(0, Math.min(100, response.resultJson().length())));
        // In full implementation: feed this back into the reasoning loop
        return this;
    }

    // ---- Helper methods ----
    private String buildSensorContext(ClassificationProtocol.ClassificationResult classification) {
        var fused = classification.fusedEvent();
        if (fused == null || fused.sensorEvents() == null) return "No sensor data available";

        StringBuilder sb = new StringBuilder("SENSOR READINGS:\n");
        fused.sensorEvents().forEach((type, event) -> {
            sb.append(String.format("  %s: %d ppm (avg=%.1f, breach=%s, trend=%s)\n",
                type, event.valuePpm(), event.rollingAvg(),
                event.thresholdBreached(), event.trendShape()));
        });

        if (fused.thermalEvent() != null) {
            sb.append(String.format("THERMAL: %.1f°C (anomaly=%s)\n",
                fused.thermalEvent().maxTemperature(),
                fused.thermalEvent().anomalyDetected()));
        }

        if (fused.audioEvent() != null) {
            var audio = fused.audioEvent();
            sb.append(String.format("AUDIO: label=%s rms=%.2f freq=%.0fHz anomaly=%s — %s\n",
                audio.label(), audio.rmsEnergy(), audio.dominantFreqHz(),
                audio.anomalyDetected(), audio.description()));
        }

        return sb.toString();
    }

    private String buildHistoryContext(List<RetrievalProtocol.PastIncident> incidents) {
        if (incidents == null || incidents.isEmpty()) return "No similar past incidents found.";

        return incidents.stream()
            .map(i -> String.format("- [%.2f similarity] %s: %s → %s",
                i.similarityScore(), i.hazardClass(), i.rootCause(), i.resolution()))
            .collect(Collectors.joining("\n"));
    }

    private String extractSection(String report, String sectionName, String fallback) {
        // Simple extraction — find section header and grab content until next section
        String lower = report.toLowerCase();
        int idx = lower.indexOf(sectionName.toLowerCase());
        if (idx == -1) return fallback;
        int start = report.indexOf('\n', idx);
        if (start == -1) return fallback;
        int end = report.indexOf('\n', start + 100);
        if (end == -1) end = Math.min(start + 500, report.length());
        return report.substring(start, end).trim();
    }
}