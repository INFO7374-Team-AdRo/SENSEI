package com.safety.clients;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import java.util.Arrays;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MongoDBService {

    private static final Logger log = LoggerFactory.getLogger(MongoDBService.class);

    private final MongoCollection<Document> collection;
    private final MongoCollection<Document> conversations;
    private final boolean available;

    public MongoDBService(String uri, String dbName) {
        MongoCollection<Document> col = null;
        MongoCollection<Document> convCol = null;
        boolean ok = false;
        try {
            // Short timeouts so a slow Atlas connection never blocks the Akka HTTP thread
            String timedUri = uri.contains("?")
                ? uri + "&connectTimeoutMS=3000&socketTimeoutMS=5000&serverSelectionTimeoutMS=3000"
                : uri + "?connectTimeoutMS=3000&socketTimeoutMS=5000&serverSelectionTimeoutMS=3000";
            MongoClient client = MongoClients.create(timedUri);
            col = client.getDatabase(dbName).getCollection("incidents");
            convCol = client.getDatabase(dbName).getCollection("conversations");
            // Ping to verify connectivity
            client.getDatabase(dbName).runCommand(new Document("ping", 1));
            ok = true;
            log.info("MongoDBService connected to Atlas (db={})", dbName);
        } catch (Exception e) {
            log.warn("MongoDB unavailable — incident persistence disabled: {}", e.getMessage());
        }
        this.collection = col;
        this.conversations = convCol;
        this.available = ok;
    }

    /**
     * Saves a fully-resolved incident document (list + report fields combined).
     * incidentMap must contain: incidentId, hazardType, severity, recommendation,
     * confidence, tier, timestamp, summary, rootCause, affectedSensors, timeline, generatedAt.
     */
    public void saveIncident(Map<String, Object> incidentMap) {
        if (!available) return;
        try {
            String id = (String) incidentMap.get("incidentId");
            Document doc = new Document("_id", id);
            incidentMap.forEach((k, v) -> {
                if (!"incidentId".equals(k)) {
                    if (v instanceof List) {
                        doc.append(k, v);
                    } else {
                        doc.append(k, v);
                    }
                }
            });
            collection.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
            log.debug("Incident {} saved to MongoDB", id);
        } catch (Exception e) {
            log.error("MongoDB write failed: {}", e.getMessage());
        }
    }

 
    public void saveInspectionIncident(Map<String, Object> eventMap) {
        if (!available) return;
        try {
            // Build a stable ID from waypointId so re-processing doesn't duplicate
            String waypointId = String.valueOf(eventMap.getOrDefault("waypointId", "unknown"));
            String id = "INSP-" + waypointId;
            Document doc = new Document("_id", id)
                .append("source",      "inspecsafe")
                .append("incidentId",  id)
                // Map InspecSafe fields to the shared incident schema the UI expects
                .append("hazardType",     eventMap.getOrDefault("anomalyType",  "Visual Anomaly"))
                .append("severity",       eventMap.getOrDefault("severity",     "Significant"))
                .append("tier",           "INSPECSAFE")
                .append("confidence",     1.0)
                .append("recommendation", buildInspectionRecommendation(eventMap));
            // Copy all event fields (image URL, sensor readings, location, etc.)
            eventMap.forEach((k, v) -> { if (v != null && !doc.containsKey(k)) doc.append(k, v); });
            collection.replaceOne(Filters.eq("_id", id), doc, new ReplaceOptions().upsert(true));
            log.debug("InspecSafe incident saved: {}", id);
        } catch (Exception e) {
            log.error("MongoDB write failed for InspecSafe event: {}", e.getMessage());
        }
    }

    private String buildInspectionRecommendation(Map<String, Object> m) {
        String grade   = String.valueOf(m.getOrDefault("safetyGrade", "Grade Two"));
        String anomaly = String.valueOf(m.getOrDefault("anomalyType", "anomaly"));
        String loc     = String.valueOf(m.getOrDefault("location",    "location"));
        String sev     = String.valueOf(m.getOrDefault("severity",    "Significant"));
        if ("Critical".equals(sev)) {
            return String.format("IMMEDIATE ACTION: %s detected at %s (%s). " +
                "Stop operations, evacuate personnel and report to safety officer.", anomaly, loc, grade);
        }
        return String.format("Corrective action required: %s at %s (%s). " +
            "Enforce PPE compliance and log violation for safety review.", anomaly, loc, grade);
    }

    /** Returns all incidents as flat maps, newest first. */
    public List<Map<String, Object>> getAllIncidents() {
        if (!available) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            collection.find()
                .sort(new Document("timestamp", -1))
                .forEach(doc -> result.add(docToMap(doc)));
        } catch (Exception e) {
            log.error("MongoDB read failed: {}", e.getMessage());
        }
        return result;
    }

    /** Returns a single incident document including report fields, or null. */
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
     * Appends a single turn (user or assistant) to the conversation history in MongoDB.
     */
    public void saveChatTurn(String conversationId, String role, String content) {
        if (!available) return;
        try {
            // Truncate to milliseconds — Java Instant can have nanosecond precision
            // but JavaScript's Date only handles up to 3 decimal places.
            Document doc = new Document()
                .append("conversationId", conversationId)
                .append("role", role)
                .append("content", content)
                .append("timestamp", java.time.Instant.now()
                    .truncatedTo(ChronoUnit.MILLIS).toString());
            conversations.insertOne(doc);
            log.debug("Chat turn saved — conv={} role={}", conversationId, role);
        } catch (Exception e) {
            log.error("MongoDB chat write failed: {}", e.getMessage());
        }
    }

    
    public List<Map<String, String>> loadChatHistory(String conversationId, int limit) {
        if (!available) return new ArrayList<>();
        List<Map<String, String>> result = new ArrayList<>();
        try {
            conversations.find(Filters.eq("conversationId", conversationId))
                .sort(new Document("timestamp", 1))
                .limit(limit)
                .forEach(doc -> {
                    Map<String, String> turn = new HashMap<>();
                    turn.put("role",    doc.getString("role"));
                    turn.put("content", doc.getString("content"));
                    result.add(turn);
                });
        } catch (Exception e) {
            log.error("MongoDB chat read failed for conv={}: {}", conversationId, e.getMessage());
        }
        return result;
    }

 
    public List<Map<String, Object>> getChatHistory(String conversationId, int limit) {
        if (!available) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            conversations.find(Filters.eq("conversationId", conversationId))
                .sort(new Document("timestamp", 1))
                .limit(limit)
                .forEach(doc -> {
                    Map<String, Object> turn = new HashMap<>();
                    turn.put("role",      doc.getString("role"));
                    turn.put("content",   doc.getString("content"));
                    turn.put("timestamp", doc.getString("timestamp"));
                    result.add(turn);
                });
        } catch (Exception e) {
            log.error("MongoDB chat history read failed for conv={}: {}", conversationId, e.getMessage());
        }
        return result;
    }

 
    public List<Map<String, Object>> listConversations(int limit) {
        if (!available) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Document> pipeline = Arrays.asList(
                new Document("$match",  new Document("role", "user")),
                new Document("$sort",   new Document("timestamp", 1)),
                new Document("$group",  new Document("_id", "$conversationId")
                    .append("title",        new Document("$first", "$content"))
                    .append("lastActivity", new Document("$max",   "$timestamp"))),
                new Document("$sort",   new Document("lastActivity", -1)),
                new Document("$limit",  limit)
            );
            conversations.aggregate(pipeline).forEach(doc -> {
                String title = doc.getString("title");
                if (title == null) title = "Untitled";
                else if (title.length() > 65) title = title.substring(0, 62) + "…";
                Map<String, Object> conv = new HashMap<>();
                conv.put("conversationId", doc.getString("_id"));
                conv.put("title",          title);
                conv.put("lastActivity",   doc.getString("lastActivity"));
                result.add(conv);
            });
        } catch (Exception e) {
            log.error("MongoDB listConversations failed: {}", e.getMessage());
        }
        return result;
    }

    public boolean isAvailable() { return available; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> docToMap(Document doc) {
        Map<String, Object> m = new HashMap<>(doc);
        // Rename _id → incidentId for API consumers
        if (m.containsKey("_id")) {
            m.put("incidentId", m.remove("_id"));
        }
        return m;
    }
}
