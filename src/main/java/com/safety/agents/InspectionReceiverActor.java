package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.InspectionProtocol;

/**
 * InspectionReceiverActor — lives on node-a.
 *
 * Registered with the Akka Cluster Receptionist so node-d's
 * VisualInspectionAgent can discover it and send InspectionEvents
 * across the cluster wire without needing to know the HTTP server's location.
 *
 * On receipt it simply hands the event to SafetyHttpServer, which
 * adds it to the rolling buffer and serves it to the dashboard.
 */
public class InspectionReceiverActor
        extends AbstractBehavior<InspectionProtocol.ReceiverCommand> {

    public static Behavior<InspectionProtocol.ReceiverCommand> create() {
        return Behaviors.setup(InspectionReceiverActor::new);
    }

    private InspectionReceiverActor(ActorContext<InspectionProtocol.ReceiverCommand> ctx) {
        super(ctx);
        ctx.getLog().info("InspectionReceiverActor started on node-a — ready to receive from node-d");
    }

    @Override
    public Receive<InspectionProtocol.ReceiverCommand> createReceive() {
        return newReceiveBuilder()
            .onMessage(InspectionProtocol.ForwardInspectionEvent.class, this::onForward)
            .build();
    }

    private Behavior<InspectionProtocol.ReceiverCommand> onForward(
            InspectionProtocol.ForwardInspectionEvent msg) {

        SafetyHttpServer http = SafetyHttpServer.getInstance();
        if (http != null) {
            http.pushInspectionEvent(msg.event());
        } else {
            getContext().getLog().warn(
                "InspectionReceiverActor: SafetyHttpServer not available — dropping event {}",
                msg.event().waypointId());
        }
        return this;
    }
}
