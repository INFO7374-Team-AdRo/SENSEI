package edu.neu.safety;

import akka.actor.typed.ActorSystem;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import edu.neu.safety.cluster.ClusterSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster node entry point for the SENSEI safety monitoring system.
 *
 * <p>Per-node behavior is selected at launch via
 * {@code -Dconfig.resource=node-X.conf} (for example {@code node-ingestion.conf}
 * or {@code node-reasoning.conf}). When no override is supplied, HOCON
 * fallback resolution lands on {@code single-jvm.conf}, which bakes every
 * cluster role into a single process — handy for local end-to-end testing
 * without spinning up four terminals.
 *
 * <p>This class does three things and nothing more:
 * <ol>
 *   <li>Load the HOCON config from the classpath.</li>
 *   <li>Create the root {@link ActorSystem} rooted at {@link ClusterSetup}.</li>
 *   <li>Register a JVM shutdown hook so Ctrl+C terminates the system
 *       cleanly instead of leaving dangling cluster members behind.</li>
 * </ol>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * JVM entry point. Boots the ActorSystem with the resolved HOCON config
     * and keeps running until the JVM is signalled to stop. Logs the key
     * cluster parameters up front so operators can tell at a glance which
     * node role this process is playing.
     *
     * @param args ignored — all config lives in HOCON, not argv
     */
    public static void main(String[] args) {
        Config config = ConfigFactory.load();

        log.info("=== SENSEI Safety Monitoring System ===");
        log.info("Cluster roles    : {}", config.getStringList("akka.cluster.roles"));
        log.info("Artery port      : {}", config.getInt("akka.remote.artery.canonical.port"));
        log.info("Seed nodes       : {}", config.getStringList("akka.cluster.seed-nodes"));
        log.info("HTTP host:port   : {}:{}",
            config.getString("safety.http.host"), config.getInt("safety.http.port"));

        ActorSystem<ClusterSetup.Command> system = ActorSystem.create(
            ClusterSetup.create(config),
            "SafetySystem",
            config
        );

        log.info("Node started at {}", system.address());

        // Graceful shutdown — without this, Ctrl+C can leave the node marked
        // as UNREACHABLE in the cluster for a few gossip rounds.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — terminating ActorSystem");
            system.terminate();
        }));
    }
}
