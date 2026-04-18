package edu.neu.safety.agents.retrieval;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import edu.neu.safety.external.MistralClient;
import edu.neu.safety.external.QdrantService;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.FusedSnapshot;
import edu.neu.safety.model.IncidentReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Vector-RAG retrieval over Qdrant. Two operations:
 *   - SearchSimilar: embed the current situation, return top-K past incidents.
 *   - StoreIncident: upsert finalized incidents back into Qdrant for self-improving RAG.
 *
 * If Qdrant is unreachable, falls back to a small bank of canned past incidents
 * keyed by classification label so the rest of the pipeline still has something to reason over.
 */
public class RetrievalAgent extends AbstractBehavior<RetrievalProtocol.Command> {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAgent.class);

    private final MistralClient mistralClient;
    private final QdrantService qdrantService;
    private int queriesPerformed = 0;
    private int totalIncidentsRetrieved = 0;
    private int incidentsStored = 0;
    private boolean qdrantConnected = false;

    /**
     * Factory. Uses exponential-backoff restarts (1s → 30s, 20% jitter) so
     * Qdrant Cloud hiccups don't thrash the agent. Parameters are pulled
     * directly from application.conf by {@link edu.neu.safety.cluster.ClusterSetup}.
     */
    public static Behavior<RetrievalProtocol.Command> create(
            String mistralApiKey, String mistralBaseUrl, String embedModel,
            String qdrantUrl, String qdrantApiKey, String qdrantCollection,
            int vectorSize) {
        return Behaviors.supervise(
            Behaviors.<RetrievalProtocol.Command>setup(context -> new RetrievalAgent(
                context, mistralApiKey, mistralBaseUrl, embedModel,
                qdrantUrl, qdrantApiKey, qdrantCollection, vectorSize))
        ).onFailure(SupervisorStrategy.restartWithBackoff(
            Duration.ofSeconds(1), Duration.ofSeconds(30), 0.2));
    }

    private RetrievalAgent(ActorContext<RetrievalProtocol.Command> context,
                           String mistralApiKey, String mistralBaseUrl, String embedModel,
                           String qdrantUrl, String qdrantApiKey, String qdrantCollection,
                           int vectorSize) {
        super(context);
        this.mistralClient = new MistralClient(mistralApiKey, mistralBaseUrl, embedModel);
        this.qdrantService = new QdrantService(qdrantUrl, qdrantCollection, qdrantApiKey);
        try {
            qdrantService.ensureCollection(vectorSize);
            qdrantConnected = true;
            log.info("RetrievalAgent: Qdrant ready at {}", qdrantUrl);
        } catch (Exception e) {
            log.warn("RetrievalAgent: Qdrant unreachable, using fallback incidents only ({})", e.getMessage());
        }
    }

    /** Message dispatch table. */
    @Override
    public Receive<RetrievalProtocol.Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(RetrievalProtocol.SearchSimilar.class, this::onSearchSimilar)
            .onMessage(RetrievalProtocol.StoreIncident.class, this::onStoreIncident)
            .onMessage(RetrievalProtocol.GetStatus.class, this::onGetStatus)
            .build();
    }

    /**
     * Embed the current situation description with Mistral, run a top-3
     * similarity search on Qdrant, and reply with the resulting past
     * incidents. Falls back to a small hard-coded bank if Qdrant is down
     * or returns no hits — the LLM still produces something reasonable
     * that way.
     */
    private Behavior<RetrievalProtocol.Command> onSearchSimilar(RetrievalProtocol.SearchSimilar cmd) {
        String situationDesc = buildSituationDescription(cmd.classification(), cmd.snapshot());
        if (qdrantConnected) {
            try {
                float[] embedding = mistralClient.embed(situationDesc).get();
                List<IncidentReport.PastIncident> incidents = qdrantService.search(embedding, 3);
                if (incidents.isEmpty()) {
                    incidents = getFallbackIncidents(cmd.classification());
                }
                queriesPerformed++;
                totalIncidentsRetrieved += incidents.size();
                log.info("Retrieved {} similar incidents for {}", incidents.size(), cmd.classification().label());
                cmd.replyTo().tell(new RetrievalProtocol.RetrievalResult(incidents));
            } catch (Exception e) {
                log.warn("Qdrant search failed, using fallback: {}", e.getMessage());
                cmd.replyTo().tell(new RetrievalProtocol.RetrievalResult(
                    getFallbackIncidents(cmd.classification())));
            }
        } else {
            cmd.replyTo().tell(new RetrievalProtocol.RetrievalResult(
                getFallbackIncidents(cmd.classification())));
        }
        return this;
    }

    /**
     * Push a finalized incident back into Qdrant so future queries can see
     * it. We deliberately skip NONE-tier incidents because those are just
     * NO_GAS samples and would swamp the corpus.
     *
     * <p>This is the "self-improving" side of the RAG loop: the more the
     * system runs, the more contextually aware the retrieval step becomes.
     */
    private Behavior<RetrievalProtocol.Command> onStoreIncident(RetrievalProtocol.StoreIncident cmd) {
        if (!qdrantConnected) return this;
        IncidentReport report = cmd.report();
        // Skip noise: only persist real incidents back into the corpus.
        if (report.escalationTier() == edu.neu.safety.model.EscalationTier.NONE) return this;

        String description = String.format("[%s, %.0f%%] sensors=%s thermal max=%.1fC",
            report.classificationLabel(),
            report.classificationConfidence() * 100,
            report.sensorValues(),
            report.maxTemp());
        String resolution = String.format("Action: %s. LLM analysis: %s",
            report.escalationAction(), report.llmAnalysis());

        try {
            float[] embedding = mistralClient.embed(description).get();
            qdrantService.upsert(report.id(), embedding, description, resolution);
            incidentsStored++;
            log.debug("Stored incident {} into Qdrant for future RAG", report.id());
        } catch (Exception e) {
            log.warn("Failed to store incident {} in Qdrant: {}", report.id(), e.getMessage());
        }
        return this;
    }

    /** Dashboard probe — exposes retrieval counters. */
    private Behavior<RetrievalProtocol.Command> onGetStatus(RetrievalProtocol.GetStatus cmd) {
        cmd.replyTo().tell(new RetrievalProtocol.RetrievalStatus(
            queriesPerformed, totalIncidentsRetrieved, incidentsStored, qdrantConnected
        ));
        return this;
    }

    /**
     * Flatten the classification + sensor snapshot into a natural-language
     * string. This is what gets embedded — keeping it natural-language rather
     * than machine-y helps the embedding land closer to real incident
     * descriptions in vector space.
     */
    private String buildSituationDescription(ClassificationResult classification, FusedSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hazard classification: ").append(classification.label())
            .append(" (confidence: ").append(String.format("%.2f", classification.confidence())).append("). ");
        sb.append("Sensor readings: ");
        for (Map.Entry<String, Double> entry : snapshot.sensorValues().entrySet()) {
            sb.append(entry.getKey()).append("=").append(String.format("%.1f", entry.getValue())).append(" ");
        }
        sb.append("Thermal: max=").append(String.format("%.1f", snapshot.maxTemp()))
            .append("C, avg=").append(String.format("%.1f", snapshot.avgTemp())).append("C");
        if (snapshot.thermalAnomaly()) sb.append(" [THERMAL ANOMALY]");
        return sb.toString();
    }

    /**
     * Small hand-written incident bank. These are realistic examples drawn
     * from OSHA case studies — enough to give the LLM something concrete
     * to ground on when Qdrant is unavailable.
     */
    private List<IncidentReport.PastIncident> getFallbackIncidents(ClassificationResult classification) {
        return switch (classification.label()) {
            case "SMOKE" -> List.of(
                new IncidentReport.PastIncident(
                    "Smoke detected in Zone B near welding station. MQ2 and MQ7 elevated. Thermal showed 65C hotspot.",
                    "Activated ventilation. Evacuated zone. Source: overheated motor bearing. Replaced bearing, incident resolved in 45 minutes.",
                    0.87f),
                new IncidentReport.PastIncident(
                    "Gradual smoke buildup in storage area. MQ7 (CO) spiked first, followed by MQ2.",
                    "Identified smoldering insulation material. Fire suppression activated. No injuries.",
                    0.82f)
            );
            case "PERFUME" -> List.of(
                new IncidentReport.PastIncident(
                    "VOC detection near chemical storage. MQ3 and MQ135 elevated. No thermal anomaly.",
                    "Source identified as cleaning solvent spill. Area ventilated. No hazard confirmed.",
                    0.79f)
            );
            case "COMBINED" -> List.of(
                new IncidentReport.PastIncident(
                    "Combined gas and smoke event in reactor area. All sensors elevated. Thermal showed 78C.",
                    "Emergency shutdown initiated. Chemical leak from valve seal. Full evacuation completed. Hazmat team deployed.",
                    0.91f),
                new IncidentReport.PastIncident(
                    "Multi-sensor alert: flammable gas + smoke + thermal anomaly near furnace.",
                    "Gas supply shut off. Fire team deployed. Root cause: cracked heat exchanger. Facility shut down for 6 hours.",
                    0.85f)
            );
            default -> List.of(
                new IncidentReport.PastIncident(
                    "Normal operations. All sensors within baseline. Periodic calibration check.",
                    "No action required. Sensors operating normally.",
                    0.95f)
            );
        };
    }
}
