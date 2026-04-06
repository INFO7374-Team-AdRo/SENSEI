
// ==================== FILE: ClassificationProtocol.java ====================
package com.safety.protocol;

import akka.actor.typed.ActorRef;
import java.time.Instant;

public class ClassificationProtocol {

    public record ClassifyRequest(
        FusionProtocol.FusedEvent fusedEvent,
        ActorRef<ClassificationResult> replyTo
    ) implements CborSerializable {}

    public record ClassificationResult(
        String hazardClass,
        double confidence,
        FusionProtocol.FusedEvent fusedEvent,
        Instant timestamp
    ) implements CborSerializable {}
}
