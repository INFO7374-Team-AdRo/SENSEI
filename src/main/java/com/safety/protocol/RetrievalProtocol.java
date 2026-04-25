
// ==================== FILE: RetrievalProtocol.java ====================
package com.safety.protocol;

import akka.actor.typed.ActorRef;
import java.util.List;
import java.util.Map;

public class RetrievalProtocol {

    public record RetrieveRequest(
        ClassificationProtocol.ClassificationResult classification,
        ActorRef<RetrievalResult> replyTo
    ) implements CborSerializable {}

    public record RetrievalResult(
        ClassificationProtocol.ClassificationResult classification,
        List<PastIncident> similarIncidents
    ) implements CborSerializable {}

    public record PastIncident(
        String incidentId,
        String hazardClass,
        String rootCause,
        String resolution,
        double similarityScore
    ) implements CborSerializable {}

    public record StoreIncident(
        String incidentId,
        String summary,
        Map<String, Object> metadata
    ) implements CborSerializable {}

    /** Free-text similarity search — used by chat to find relevant past incidents. */
    public record TextQueryRequest(
        String queryText,
        int topK,
        ActorRef<TextQueryResult> replyTo
    ) implements CborSerializable {}

    public record TextQueryResult(
        List<PastIncident> incidents
    ) implements CborSerializable {}
}