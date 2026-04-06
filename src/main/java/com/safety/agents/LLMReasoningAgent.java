package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.safety.clients.MistralClient;
import com.safety.protocol.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LLMReasoningAgent extends AbstractBehavior<LLMProtocol.Command> {

    private final MistralClient mistral;
    private final Gson gson = new Gson();

    public static Behavior<LLMProtocol.Command> create(MistralClient mistralClient) {
        return Behaviors.setup(ctx -> new LLMReasoningAgent(ctx, mistralClient));
    }

    private LLMReasoningAgent(ActorContext<LLMProtocol.Command> context, MistralClient mistral) {
        super(context);
        this.mistral = mistral;
        context.getLog().info("LLMReasoningAgent started");
    }

    @Override
    public Receive<LLMProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(LLMProtocol.ReasoningRequest.class, this::onReasoningRequest)
            .onMessage(LLMProtocol.GenerateReport.class, this::onGenerateReport)
            .onMessage(LLMProtocol.NLQuery.class, this::onNLQuery)
            .onMessage(LLMProtocol.ToolResponse.class, this::onToolResponse)
            .build();
    }

    // ---- Capability 1 + 2: Multi-step reasoning + Root cause analysis ----
    private Behavior<LLMProtocol.Command> onReasoningRequest(LLMProtocol.ReasoningRequest request) {
        var classification = request.context().classification();
        var similarIncidents = request.context().similarIncidents();

        // Build context for the LLM
        String sensorContext = buildSensorContext(classification);
        String historyContext = buildHistoryContext(similarIncidents);

        String systemPrompt = """
            You are an industrial safety agent analyzing gas sensor data from a factory floor.
            You have access to 7 MQ gas sensors (MQ2: flammable, MQ3: alcohol, MQ5: LPG, 
            MQ6: butane, MQ7: carbon monoxide, MQ8: hydrogen, MQ135: air quality) and 
            a thermal camera.
            
            Perform multi-step root cause analysis:
            1. Examine which sensors triggered and which didn't
            2. Cross-reference sensor combinations to identify the gas type
            3. Compare with similar past incidents
            4. Determine the causal chain
            5. Assess severity and recommend actions
            
            Respond ONLY in JSON with these fields:
            {
              "hazardType": "string",
              "severity": "low|medium|high|critical",
              "causalChain": "step-by-step reasoning of what caused this",
              "evidenceSteps": ["step1", "step2", ...],
              "recommendation": "what to do",
              "explanation": "human-readable summary"
            }
            """;

        String userMessage = String.format("""
            CURRENT EVENT:
            Classification: %s (confidence: %.2f)
            %s
            
            SIMILAR PAST INCIDENTS:
            %s
            
            Analyze this event. What is the root cause and what should we do?
            """,
            classification.hazardClass(),
            classification.confidence(),
            sensorContext,
            historyContext
        );

        try {
            String response = mistral.chat(systemPrompt, userMessage);
            JsonObject parsed = gson.fromJson(response, JsonObject.class);

            List<String> evidenceSteps = new ArrayList<>();
            if (parsed.has("evidenceSteps")) {
                parsed.getAsJsonArray("evidenceSteps")
                    .forEach(e -> evidenceSteps.add(e.getAsString()));
            }

            var result = new LLMProtocol.ReasoningResult(
                parsed.has("hazardType") ? parsed.get("hazardType").getAsString() : classification.hazardClass(),
                parsed.has("severity") ? parsed.get("severity").getAsString() : "medium",
                parsed.has("causalChain") ? parsed.get("causalChain").getAsString() : "Unknown",
                evidenceSteps,
                parsed.has("recommendation") ? parsed.get("recommendation").getAsString() : "Investigate manually",
                parsed.has("explanation") ? parsed.get("explanation").getAsString() : "Analysis pending",
                evidenceSteps.size(),
                Instant.now()
            );

            getContext().getLog().info("LLM reasoning complete: {} severity={} steps={}",
                result.hazardType(), result.severity(), result.reasoningSteps());

            request.replyTo().tell(result);

        } catch (Exception e) {
            getContext().getLog().error("Mistral API call failed: {}", e.getMessage());

            // Fallback response
            var fallback = new LLMProtocol.ReasoningResult(
                classification.hazardClass(), "medium",
                "API unavailable — using classification result directly",
                List.of("Fallback: LLM unavailable"),
                "Investigate manually — LLM reasoning unavailable",
                "Classification detected " + classification.hazardClass() + " with " +
                    String.format("%.1f%%", classification.confidence() * 100) + " confidence",
                0, Instant.now()
            );
            request.replyTo().tell(fallback);
        }

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

        try {
            String report = mistral.chatFreeform(systemPrompt, context.toString());

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

        } catch (Exception e) {
            getContext().getLog().error("Report generation failed: {}", e.getMessage());
            request.replyTo().tell(new LLMProtocol.IncidentReport(
                request.incidentId(),
                "Report generation failed", "", "", "", "",
                "Error: " + e.getMessage()
            ));
        }

        return this;
    }

    // ---- Capability 4: Natural language querying ----
    private Behavior<LLMProtocol.Command> onNLQuery(LLMProtocol.NLQuery request) {
        String systemPrompt = """
            You are a safety monitoring assistant. An operator is asking about the current
            state of the industrial safety monitoring system. Answer concisely and accurately.
            
            Categorize the query as one of: status, history, explanation, unknown.
            
            Respond in JSON:
            {
              "answer": "your response",
              "queryType": "status|history|explanation|unknown",
              "sourcesUsed": ["sensor_data", "incident_log", "llm_knowledge"]
            }
            """;

        try {
            String response = mistral.chat(systemPrompt, request.query());
            JsonObject parsed = gson.fromJson(response, JsonObject.class);

            List<String> sources = new ArrayList<>();
            if (parsed.has("sourcesUsed")) {
                parsed.getAsJsonArray("sourcesUsed")
                    .forEach(e -> sources.add(e.getAsString()));
            }

            var result = new LLMProtocol.NLResponse(
                parsed.has("answer") ? parsed.get("answer").getAsString() : "Unable to process query",
                parsed.has("queryType") ? parsed.get("queryType").getAsString() : "unknown",
                sources
            );

            request.replyTo().tell(result);

        } catch (Exception e) {
            getContext().getLog().error("NL query failed: {}", e.getMessage());
            request.replyTo().tell(new LLMProtocol.NLResponse(
                "Query processing failed: " + e.getMessage(),
                "unknown",
                List.of()
            ));
        }

        return this;
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
