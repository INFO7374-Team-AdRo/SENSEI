package edu.neu.safety.cluster;

/**
 * String constants for Akka cluster roles. Each node's role set is declared
 * in node-a.conf / node-b.conf / node-c.conf and is what {@link ClusterSetup}
 * consults to decide which agents to spawn locally.
 *
 * <ul>
 *   <li>{@link #INGESTION} / {@link #FUSION} — sensor-side of the pipeline
 *       (thermal, fusion, classification).</li>
 *   <li>{@link #REASONING} / {@link #ESCALATION} — retrieval (Qdrant RAG),
 *       Mistral LLM agent, and the tier-based escalation agent.</li>
 *   <li>{@link #ORCHESTRATOR} / {@link #DASHBOARD} — pipeline coordinator and
 *       the Akka HTTP server that serves the web UI.</li>
 *   <li>{@link #PERSISTENCE} — nodes that host the Akka Persistence journal.
 *       Shard entities themselves live on every node.</li>
 * </ul>
 *
 * Using string constants (instead of an enum) because akka.cluster.roles
 * in HOCON is itself a list of strings — fewer conversions this way.
 */
public final class ClusterRoles {
    public static final String INGESTION = "ingestion";
    public static final String FUSION = "fusion";
    public static final String REASONING = "reasoning";
    public static final String ESCALATION = "escalation";
    public static final String ORCHESTRATOR = "orchestrator";
    public static final String PERSISTENCE = "persistence";
    public static final String DASHBOARD = "dashboard";

    private ClusterRoles() {}
}
