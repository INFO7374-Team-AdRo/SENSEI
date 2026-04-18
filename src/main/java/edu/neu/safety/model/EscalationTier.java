package edu.neu.safety.model;

/**
 * The four escalation levels the system can take for a given incident. The
 * EscalationAgent picks one based on classification label, confidence, and
 * whether the thermal camera saw an anomaly at the same time.
 *
 * Ordering is intentional: ordinal() grows with severity so we can compare
 * tiers directly ({@code tier.ordinal() >= T2_ALERT.ordinal()}) without a
 * separate severity map.
 *
 * <ul>
 *   <li>{@link #NONE} — no action, telemetry only.</li>
 *   <li>{@link #T1_LOG} — record the event, no operator alert.</li>
 *   <li>{@link #T2_ALERT} — alert raised on the dashboard, operator should acknowledge.</li>
 *   <li>{@link #T3_SHUTDOWN} — hard hazard, trigger shutdown procedure.</li>
 * </ul>
 */
public enum EscalationTier {
    NONE,
    T1_LOG,
    T2_ALERT,
    T3_SHUTDOWN
}
