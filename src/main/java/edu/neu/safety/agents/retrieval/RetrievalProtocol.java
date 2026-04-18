package edu.neu.safety.agents.retrieval;

import akka.actor.typed.ActorRef;
import edu.neu.safety.model.CborSerializable;
import edu.neu.safety.model.ClassificationResult;
import edu.neu.safety.model.FusedSnapshot;
import edu.neu.safety.model.IncidentReport;

import java.util.List;

/**
 * Messages for the {@link RetrievalAgent}. Two kinds of work: searching the
 * vector store for similar past incidents, and pushing freshly finalized
 * incidents back into it.
 */
public interface RetrievalProtocol {

    /** Parent marker for inbound commands. */
    sealed interface Command extends CborSerializable permits
        SearchSimilar, StoreIncident, GetStatus {}

    /**
     * Find top-K past incidents similar to the current situation. The agent
     * replies with a {@link RetrievalResult} on the provided replyTo.
     */
    record SearchSimilar(
        ClassificationResult classification,
        FusedSnapshot snapshot,
        ActorRef<RetrievalResult> replyTo
    ) implements Command {}

    /**
     * Upsert a completed incident into Qdrant. Fire-and-forget — the reply
     * would be pointless because the orchestrator doesn't block on storage.
     * This is what makes the RAG loop self-improving.
     */
    record StoreIncident(
        IncidentReport report
    ) implements Command {}

    /** Ask-pattern status query. */
    record GetStatus(ActorRef<RetrievalStatus> replyTo) implements Command {}

    /** Reply carrying the retrieved past incidents. */
    record RetrievalResult(
        List<IncidentReport.PastIncident> incidents
    ) implements CborSerializable {}

    /** Dashboard counters. */
    record RetrievalStatus(
        int queriesPerformed,
        int totalIncidentsRetrieved,
        int incidentsStored,
        boolean qdrantConnected
    ) implements CborSerializable {}
}
