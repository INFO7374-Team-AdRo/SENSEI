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
    /** Free-text similarity search triggered by chat queries. */
    public record TextQuery(RetrievalProtocol.TextQueryRequest request) implements Command {}

    private final QdrantClient qdrant;
    private final MistralClient mistral; // for embedding
    private boolean qdrantAvailable = false;

    public static Behavior<Command> create(QdrantClient qdrant, MistralClient mistral) {
        return Behaviors.setup(ctx -> new RetrievalAgent(ctx, qdrant, mistral));
    }

    private RetrievalAgent(ActorContext<Command> context,
                           QdrantClient qdrant, MistralClient mistral) {
        super(context);
        this.qdrant = qdrant;
        this.mistral = mistral;

        // Initialize collection — gracefully degrade if Qdrant is unavailable
        try {
            qdrant.ensureCollection(1024); // mistral-embed returns 1024-dim vectors
            this.qdrantAvailable = true;
            context.getLog().info("RetrievalAgent started — Qdrant collection ready (dim=1024)");
        } catch (java.io.IOException e) {
            this.qdrantAvailable = false;
            context.getLog().warn("Qdrant unavailable — RAG disabled (will retry on next query): {}", e.getMessage());
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Retrieve.class, this::onRetrieve)
            .onMessage(Store.class, this::onStore)
            .onMessage(TextQuery.class, this::onTextQuery)
            .build();
    }

    private Behavior<Command> onRetrieve(Retrieve msg) {
        var request = msg.request();
        var classification = request.classification();

        // If Qdrant was unavailable at startup, try to initialize now
        if (!qdrantAvailable) {
            try {
                qdrant.ensureCollection(1024);
                qdrantAvailable = true;
                getContext().getLog().info("Qdrant reconnected — RAG enabled");
            } catch (java.io.IOException e) {
                getContext().getLog().debug("Qdrant still unavailable — returning empty RAG results");
                request.replyTo().tell(new RetrievalProtocol.RetrievalResult(classification, List.of()));
                return this;
            }
        }

        // Build text to embed from the classification context
        String queryText = String.format("%s event, confidence %.2f",
            classification.hazardClass(), classification.confidence());

        // Embed the query — on failure return empty rather than crashing
        float[] queryVector;
        List<QdrantClient.SearchResult> results;
        try {
            queryVector = mistral.embed(queryText);
            results = qdrant.search(queryVector, 3);
        } catch (java.io.IOException e) {
            getContext().getLog().warn("Qdrant search failed — returning empty RAG results: {}", e.getMessage());
            qdrantAvailable = false; // will retry next call
            request.replyTo().tell(new RetrievalProtocol.RetrievalResult(classification, List.of()));
            return this;
        }

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

        return this;
    }

    /**
     * Free-text search used by the chat pipeline.
     * Embeds the user's natural-language query and returns the top-K most
     * similar past incidents from Qdrant, so the LLM can reference real history.
     */
    private Behavior<Command> onTextQuery(TextQuery msg) {
        var request = msg.request();

        if (!qdrantAvailable) {
            try {
                qdrant.ensureCollection(1024);
                qdrantAvailable = true;
                getContext().getLog().info("Qdrant reconnected (text query)");
            } catch (java.io.IOException e) {
                getContext().getLog().debug("Qdrant still unavailable — returning empty chat RAG results");
                request.replyTo().tell(new RetrievalProtocol.TextQueryResult(List.of()));
                return this;
            }
        }

        try {
            float[] vector = mistral.embed(request.queryText());
            List<QdrantClient.SearchResult> results = qdrant.search(vector, request.topK());

            List<RetrievalProtocol.PastIncident> incidents = new ArrayList<>();
            for (var r : results) {
                incidents.add(new RetrievalProtocol.PastIncident(
                    r.id(), r.hazardClass(), r.rootCause(), r.resolution(), r.score()
                ));
            }

            getContext().getLog().info("Chat RAG: found {} similar incidents for query \"{}\"",
                incidents.size(), request.queryText().substring(0, Math.min(60, request.queryText().length())));

            request.replyTo().tell(new RetrievalProtocol.TextQueryResult(incidents));
        } catch (java.io.IOException e) {
            getContext().getLog().warn("Qdrant text search failed: {}", e.getMessage());
            qdrantAvailable = false;
            request.replyTo().tell(new RetrievalProtocol.TextQueryResult(List.of()));
        }

        return this;
    }

    private Behavior<Command> onStore(Store msg) {
        if (!qdrantAvailable) {
            getContext().getLog().debug("Qdrant unavailable — skipping store for incident {}", msg.incident().incidentId());
            return this;
        }

        var incident = msg.incident();

        // Embed the incident summary — log and skip on failure rather than crashing
        float[] vector;
        try {
            vector = mistral.embed(incident.summary());
        } catch (java.io.IOException e) {
            getContext().getLog().warn("Embedding failed for incident {} — skipping Qdrant store: {}", incident.incidentId(), e.getMessage());
            return this;
        }

        if (vector == null || vector.length == 0) {
            getContext().getLog().warn("Empty embedding returned for incident {} — skipping Qdrant store", incident.incidentId());
            return this;
        }

        // Build payload
        Map<String, Object> payload = new HashMap<>(incident.metadata());
        payload.put("summary", incident.summary());

        try {
            qdrant.upsert(incident.incidentId(), vector, payload);
            getContext().getLog().info("Stored incident {} in Qdrant (vector dim={})", incident.incidentId(), vector.length);
        } catch (java.io.IOException e) {
            getContext().getLog().warn("Qdrant upsert failed for incident {} — skipping: {}", incident.incidentId(), e.getMessage());
            qdrantAvailable = false; // will retry on next retrieve
        }

        return this;
    }
}