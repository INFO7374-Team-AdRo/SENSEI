
// ==================== FILE: EscalationProtocol.java ====================
package com.safety.protocol;

import java.time.Instant;

public class EscalationProtocol {

    public enum Tier { T1_LOG, T2_ALERT, T3_SHUTDOWN }

    public record EscalationRequest(
        LLMProtocol.ReasoningResult reasoning,
        ClassificationProtocol.ClassificationResult classification
    ) implements CborSerializable {}

    public record EscalationDecision(
        Tier tier,
        String action,
        LLMProtocol.ReasoningResult reasoning,
        Instant timestamp
    ) implements CborSerializable {}
}