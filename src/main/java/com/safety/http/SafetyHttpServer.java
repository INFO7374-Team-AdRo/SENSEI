package com.safety.http;

import akka.actor.typed.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static akka.http.javadsl.server.Directives.*;
import static akka.http.javadsl.server.PathMatchers.segment;

public class SafetyHttpServer {

    private final ActorSystem<?> system;
    private final Gson gson = new Gson();

    private final List<String> recentSensorEvents = new ArrayList<>();
    private final List<String> recentEscalationEvents = new ArrayList<>();

    public SafetyHttpServer(ActorSystem<?> system) {
        this.system = system;
    }

    public void pushSensorEvent(Map<String, Object> event) {
        String json = gson.toJson(event);
        synchronized (recentSensorEvents) {
            recentSensorEvents.add(json);
            if (recentSensorEvents.size() > 100) {
                recentSensorEvents.remove(0);
            }
        }
    }

    public void pushEscalationEvent(Map<String, Object> event) {
        String json = gson.toJson(event);
        synchronized (recentEscalationEvents) {
            recentEscalationEvents.add(json);
            if (recentEscalationEvents.size() > 100) {
                recentEscalationEvents.remove(0);
            }
        }
    }

    private Route jsonResponse(String json) {
        return complete(
            HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(
                    akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
                    json
                )
        );
    }

    public Route createRoutes() {
        return respondWithHeaders(
            List.of(
                HttpHeader.parse("Access-Control-Allow-Origin", "*"),
                HttpHeader.parse("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
                HttpHeader.parse("Access-Control-Allow-Headers", "Content-Type")
            ),
            () -> concat(
                pathEndOrSingleSlash(() ->
                    jsonResponse("{\"message\":\"Industrial Safety Monitoring API\"}")
                ),
                pathPrefix("health", () ->
                    pathEndOrSingleSlash(() ->
                        jsonResponse("{\"status\":\"ok\"}")
                    )
                ),
                pathPrefix("api-sensors", () ->
                    pathEndOrSingleSlash(() -> {
                        String json;
                        synchronized (recentSensorEvents) {
                            json = gson.toJson(new ArrayList<>(recentSensorEvents));
                        }
                        return jsonResponse(json);
                    })
                ),
                pathPrefix("api-escalations", () ->
                    pathEndOrSingleSlash(() -> {
                        String json;
                        synchronized (recentEscalationEvents) {
                            json = gson.toJson(new ArrayList<>(recentEscalationEvents));
                        }
                        return jsonResponse(json);
                    })
                ),
                pathPrefix("api-status", () ->
                    pathEndOrSingleSlash(() -> {
                        Map<String, Object> status = Map.of(
                            "sensorShards", 7,
                            "recentSensorEvents", recentSensorEvents.size(),
                            "recentEscalationEvents", recentEscalationEvents.size(),
                            "clusterNode", "127.0.0.1:2551"
                        );
                        return jsonResponse(gson.toJson(status));
                    })
                )
            )
        );
    }

    public void start(String host, int port) {
        Http.get(system).newServerAt(host, port)
            .bind(createRoutes())
            .whenComplete((binding, err) -> {
                if (err != null) {
                    system.log().error("HTTP server failed: {}", err.getMessage());
                } else {
                    system.log().info("HTTP server started at http://{}:{}", host, port);
                }
            });
    }
}