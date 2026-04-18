package edu.neu.safety.model;

import java.util.List;
import java.util.Map;

/**
 * The canonical "end of pipeline" report. By the time an IncidentReport
 * exists, the orchestrator has already run fusion → classification →
 * retrieval → reasoning → escalation, so this record contains everything
 * the dashboard needs to render one event plus everything MongoDB needs to
 * archive it.
 *
 * A report is produced per fused snapshot, not only for hazardous ones —
 * NO_GAS samples still flow through so the operator can see the system is
 * alive. MongoDB writes are async (fire-and-forget on a CompletableFuture)
 * to keep the main pipeline from stalling on Atlas cold-starts.
 */
public record IncidentReport(
    String id,
    Map<String, Double> sensorValues,
    double maxTemp,
    double avgTemp,
    String classificationLabel,
    float classificationConfidence,
    String groundTruthLabel,
    String llmAnalysis,
    String llmRecommendation,
    EscalationTier escalationTier,
    String escalationAction,
    List<PastIncident> similarIncidents,
    Map<String, String> agentStates,
    long timestampMs
) implements CborSerializable {

    /**
     * A lightweight view of a prior incident returned by the RetrievalAgent's
     * vector search against Qdrant. Only the fields the dashboard renders
     * and that the LLM is grounded on are kept here; the full historical
     * record still lives in Mongo.
     *
     * @param description      short natural-language summary of the past event
     * @param resolution       what the operator ended up doing
     * @param similarityScore  cosine similarity from Qdrant, in [0,1]
     */
    public record PastIncident(
        String description,
        String resolution,
        float similarityScore
    ) implements CborSerializable {}
}
