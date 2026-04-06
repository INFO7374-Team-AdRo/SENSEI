package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.clients.MistralClient;
import com.safety.clients.QdrantClient;
import com.safety.protocol.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RetrievalAgent extends AbstractBehavior<RetrievalAgent.Command> {

    public interface Command {}
    public record Retrieve(RetrievalProtocol.RetrieveRequest request) implements Command {}
    public record Store(RetrievalProtocol.StoreIncident incident) implements Command {}

    private final QdrantClient qdrant;
    private final MistralClient mistral; // for embedding
    private boolean collectionReady = false;

    public static Behavior<Command> create(QdrantClient qdrant, MistralClient mistral) {
        return Behaviors.setup(ctx -> new RetrievalAgent(ctx, qdrant, mistral));
    }

    private RetrievalAgent(ActorContext<Command> context,
                           QdrantClient qdrant, MistralClient mistral) {
        super(context);
        this.qdrant = qdrant;
        this.mistral = mistral;

        // Initialize collection
        try {
            qdrant.ensureCollection(1024); // mistral-embed dimension
            collectionReady = true;
            context.getLog().info("RetrievalAgent started — Qdrant collection ready");
        } catch (Exception e) {
            context.getLog().warn("Qdrant collection init failed: {} — will retry on first use", e.getMessage());
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Retrieve.class, this::onRetrieve)
            .onMessage(Store.class, this::onStore)
            .build();
    }

    private Behavior<Command> onRetrieve(Retrieve msg) {
        var request = msg.request();
        var classification = request.classification();

        try {
            // Build text to embed from the classification context
            String queryText = String.format("%s event, confidence %.2f",
                classification.hazardClass(), classification.confidence());

            // Embed the query
            float[] queryVector = mistral.embed(queryText);

            // Search Qdrant
            List<QdrantClient.SearchResult> results = qdrant.search(queryVector, 3);

            // Convert to protocol format
            List<RetrievalProtocol.PastIncident> incidents = new ArrayList<>();
            for (var r : results) {
                incidents.add(new RetrievalProtocol.PastIncident(
                    r.id(), r.hazardClass(), r.rootCause(), r.resolution(), r.score()
                ));
            }

            getContext().getLog().info("Retrieved {} similar incidents for {} event",
                incidents.size(), classification.hazardClass());

            request.replyTo().tell(new RetrievalProtocol.RetrievalResult(
                classification, incidents
            ));

        } catch (Exception e) {
            getContext().getLog().warn("Retrieval failed: {} — returning empty results", e.getMessage());
            request.replyTo().tell(new RetrievalProtocol.RetrievalResult(
                classification, List.of()
            ));
        }

        return this;
    }

    private Behavior<Command> onStore(Store msg) {
        var incident = msg.incident();

        try {
            // Embed the incident summary
            float[] vector = mistral.embed(incident.summary());

            // Build payload
            Map<String, Object> payload = new HashMap<>(incident.metadata());
            payload.put("summary", incident.summary());

            // Store in Qdrant
            qdrant.upsert(incident.incidentId(), vector, payload);

            getContext().getLog().info("Stored incident {} in Qdrant", incident.incidentId());

        } catch (Exception e) {
            getContext().getLog().error("Failed to store incident {}: {}",
                incident.incidentId(), e.getMessage());
        }

        return this;
    }
}
