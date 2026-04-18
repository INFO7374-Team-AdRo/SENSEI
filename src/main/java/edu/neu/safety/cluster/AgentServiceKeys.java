package edu.neu.safety.cluster;

import akka.actor.typed.receptionist.ServiceKey;
import edu.neu.safety.agents.classification.ClassificationProtocol;
import edu.neu.safety.agents.escalation.EscalationProtocol;
import edu.neu.safety.agents.fusion.FusionProtocol;
import edu.neu.safety.agents.reasoning.ReasoningProtocol;
import edu.neu.safety.agents.retrieval.RetrievalProtocol;
import edu.neu.safety.agents.thermal.ThermalProtocol;

/**
 * Central place for the {@link ServiceKey}s we use with the cluster
 * Receptionist. Each non-shard agent registers itself under exactly one of
 * these keys at startup, and the orchestrator subscribes to all six of them.
 * Only once every key has resolved to at least one {@code ActorRef} does the
 * orchestrator wire up the pipeline — that way the system doesn't tick until
 * every role is actually reachable across the cluster.
 *
 * <p>Keeping the keys here (rather than on each protocol) means the
 * discovery contract lives in one file, which is easier to reason about when
 * rolling out a new node configuration.
 */
public final class AgentServiceKeys {

    /** Thermal camera agent — one per cluster. */
    public static final ServiceKey<ThermalProtocol.Command> THERMAL =
        ServiceKey.create(ThermalProtocol.Command.class, "thermal-agent");

    /** Fusion agent — correlates sensor + thermal into one snapshot. */
    public static final ServiceKey<FusionProtocol.Command> FUSION =
        ServiceKey.create(FusionProtocol.Command.class, "fusion-agent");

    /** Classification agent — runs the ONNX model on fused snapshots. */
    public static final ServiceKey<ClassificationProtocol.Command> CLASSIFICATION =
        ServiceKey.create(ClassificationProtocol.Command.class, "classification-agent");

    /** Retrieval agent — Qdrant vector search for similar past incidents. */
    public static final ServiceKey<RetrievalProtocol.Command> RETRIEVAL =
        ServiceKey.create(RetrievalProtocol.Command.class, "retrieval-agent");

    /** Reasoning agent — wraps the Mistral chat API. */
    public static final ServiceKey<ReasoningProtocol.Command> REASONING =
        ServiceKey.create(ReasoningProtocol.Command.class, "reasoning-agent");

    /** Escalation agent — maps classifications onto T1/T2/T3 action tiers. */
    public static final ServiceKey<EscalationProtocol.Command> ESCALATION =
        ServiceKey.create(EscalationProtocol.Command.class, "escalation-agent");

    private AgentServiceKeys() {}
}
