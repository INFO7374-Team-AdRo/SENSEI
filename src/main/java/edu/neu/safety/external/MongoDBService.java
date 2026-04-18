package edu.neu.safety.external;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import edu.neu.safety.model.IncidentReport;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the MongoDB Java driver used to archive finalized
 * incident reports and to answer historical queries from the dashboard.
 *
 * <p>The collection we care about is fixed as {@code incidents} — one
 * document per {@link IncidentReport}, keyed on the incident id so repeat
 * writes upsert rather than duplicate.
 *
 * <p>Two practical notes behind the design:
 * <ol>
 *   <li>MongoDB Atlas tends to cold-start slowly after idle periods. We
 *       attach aggressive {@code connectTimeoutMS / socketTimeoutMS /
 *       serverSelectionTimeoutMS} parameters so the Akka HTTP thread never
 *       hangs more than a few seconds on a dead connection.</li>
 *   <li>Writes and reads block on the driver, so callers are expected to
 *       wrap these methods in {@code CompletableFuture.supplyAsync} instead
 *       of calling them from inside an actor's {@code onMessage} body.</li>
 * </ol>
 *
 * <p>If the initial ping fails we log a warning and flip the service into a
 * disabled state — {@link #isAvailable()} returns false, writes no-op, and
 * reads return empty collections. This lets the rest of the system run for
 * demos even without an Atlas connection.
 */
public class MongoDBService {

    private static final Logger log = LoggerFactory.getLogger(MongoDBService.class);

    private final MongoCollection<Document> collection;
    private final boolean available;

    /**
     * Build a connection to Mongo. On any failure (bad URI, DNS, auth,
     * network) the service silently flips into disabled mode — callers just
     * see no-op writes and empty reads.
     *
     * @param uri    full Mongo connection string, with or without query params
     * @param dbName database name to use (collection name is fixed to "incidents")
     */
    public MongoDBService(String uri, String dbName) {
        MongoCollection<Document> col = null;
        boolean ok = false;
        try {
            String opts = "connectTimeoutMS=3000&socketTimeoutMS=5000&serverSelectionTimeoutMS=3000";
            String timedUri;
            if (uri.contains("?")) {
                timedUri = uri + "&" + opts;
            } else {
                // Driver requires a path segment (even empty) before the query string:
                // mongodb://host:port/?opts   — not   mongodb://host:port?opts
                String base = uri.endsWith("/") ? uri : uri + "/";
                timedUri = base + "?" + opts;
            }
            MongoClient client = MongoClients.create(timedUri);
            col = client.getDatabase(dbName).getCollection("incidents");
            client.getDatabase(dbName).runCommand(new Document("ping", 1));
            ok = true;
            log.info("MongoDB connected (db={}, collection=incidents)", dbName);
        } catch (Exception e) {
            log.warn("MongoDB unavailable — incident persistence disabled: {}", e.getMessage());
        }
        this.collection = col;
        this.available = ok;
    }

    /** @return true if the initial ping succeeded; false otherwise. */
    public boolean isAvailable() { return available; }

    /**
     * Upsert a single incident keyed by its id. Swallows any driver
     * exception — we never want an archive write to take down the live
     * pipeline, and the original report is also logged on the dashboard.
     */
    public void saveIncident(IncidentReport report) {
        if (!available) return;
        try {
            Document doc = new Document("_id", report.id());
            doc.append("classificationLabel", report.classificationLabel());
            doc.append("classificationConfidence", (double) report.classificationConfidence());
            doc.append("llmAnalysis", report.llmAnalysis());
            doc.append("llmRecommendation", report.llmRecommendation());
            doc.append("escalationTier", report.escalationTier().name());
            doc.append("escalationAction", report.escalationAction());
            doc.append("maxTemp", report.maxTemp());
            doc.append("avgTemp", report.avgTemp());
            doc.append("sensorValues", new Document(report.sensorValues()));
            doc.append("agentStates", new Document(report.agentStates()));
            doc.append("timestamp", report.timestampMs());

            List<Document> similar = new ArrayList<>();
            for (IncidentReport.PastIncident pi : report.similarIncidents()) {
                similar.add(new Document()
                    .append("description", pi.description())
                    .append("resolution", pi.resolution())
                    .append("similarityScore", (double) pi.similarityScore()));
            }
            doc.append("similarIncidents", similar);

            collection.replaceOne(Filters.eq("_id", report.id()), doc,
                new ReplaceOptions().upsert(true));
            log.debug("Incident {} saved to MongoDB", report.id());
        } catch (Exception e) {
            log.error("MongoDB write failed: {}", e.getMessage());
        }
    }

    /**
     * Return the 200 most recent incidents in descending time order. Used by
     * the /api-history endpoint for the dashboard's "history" panel.
     */
    public List<Map<String, Object>> getAllIncidents() {
        if (!available) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            collection.find()
                .sort(Sorts.descending("timestamp"))
                .limit(200)
                .forEach(doc -> result.add(docToMap(doc)));
        } catch (Exception e) {
            log.error("MongoDB read failed: {}", e.getMessage());
        }
        return result;
    }

    /** Fetch one incident by its id, or null if it doesn't exist / service is down. */
    public Map<String, Object> getIncidentById(String id) {
        if (!available) return null;
        try {
            Document doc = collection.find(Filters.eq("_id", id)).first();
            return doc != null ? docToMap(doc) : null;
        } catch (Exception e) {
            log.error("MongoDB read failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Convert a BSON Document into a plain Map and rename the Mongo-internal
     * {@code _id} key to {@code incidentId} so it serializes cleanly over
     * JSON on the HTTP layer.
     */
    private Map<String, Object> docToMap(Document doc) {
        Map<String, Object> m = new HashMap<>(doc);
        if (m.containsKey("_id")) {
            m.put("incidentId", m.remove("_id"));
        }
        return m;
    }
}
