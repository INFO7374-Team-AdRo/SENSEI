"use client";

import { useEffect, useRef, useState } from "react";
import { Header } from "@/components/panels/header";
import { SensorPanel } from "@/components/panels/sensor-panel";
import { ClassificationPanel } from "@/components/panels/classification-panel";
import { EscalationPanel, type EscalationEntry } from "@/components/panels/escalation-panel";
import { LLMPanel } from "@/components/panels/llm-panel";
import { FaultPanel } from "@/components/panels/fault-panel";
import { ChatPanel } from "@/components/panels/chat-panel";
import { EventLog } from "@/components/panels/event-log";
import { SensorHistoryChart, type SensorTick } from "@/components/panels/sensor-history-chart";
import { AccuracyPanel } from "@/components/panels/accuracy-panel";
import type { IncidentReport } from "@/lib/api";

const MAX_LOG = 80;
const MAX_ESCALATIONS = 30;
const MAX_HISTORY = 60;

export default function Dashboard() {
  const [connected, setConnected] = useState(false);
  const [eventCount, setEventCount] = useState(0);
  const [latest, setLatest] = useState<IncidentReport | null>(null);
  const [log, setLog] = useState<IncidentReport[]>([]);
  const [escalations, setEscalations] = useState<EscalationEntry[]>([]);
  const [history, setHistory] = useState<SensorTick[]>([]);
  const [debug, setDebug] = useState(false);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    setDebug(new URLSearchParams(window.location.search).get("debug") === "1");
  }, []);

  useEffect(() => {
    const sseUrl =
      process.env.NEXT_PUBLIC_SSE_URL ?? "http://localhost:8080/events";
    function open() {
      if (sourceRef.current) sourceRef.current.close();
      const es = new EventSource(sseUrl);
      sourceRef.current = es;
      es.onopen = () => setConnected(true);
      es.onerror = () => setConnected(false);
      es.addEventListener("incident", (ev) => {
        try {
          const data = JSON.parse((ev as MessageEvent).data) as IncidentReport;
          setEventCount((c) => c + 1);
          setLatest(data);
          setLog((arr) => [data, ...arr].slice(0, MAX_LOG));
          if (data.escalationTier && data.escalationTier !== "NONE") {
            setEscalations((arr) =>
              [
                {
                  ts: data.timestampMs ?? Date.now(),
                  tier: data.escalationTier!,
                  action: data.escalationAction ?? "",
                },
                ...arr,
              ].slice(0, MAX_ESCALATIONS)
            );
          }
          if (data.sensorValues) {
            const tick: SensorTick = {
              t: data.timestampMs ?? Date.now(),
              MQ2: data.sensorValues.MQ2 ?? 0,
              MQ3: data.sensorValues.MQ3 ?? 0,
              MQ5: data.sensorValues.MQ5 ?? 0,
              MQ6: data.sensorValues.MQ6 ?? 0,
              MQ7: data.sensorValues.MQ7 ?? 0,
              MQ8: data.sensorValues.MQ8 ?? 0,
              MQ135: data.sensorValues.MQ135 ?? 0,
            };
            setHistory((arr) => [...arr, tick].slice(-MAX_HISTORY));
          }
        } catch {
          /* ignore */
        }
      });
    }
    open();
    const onVis = () => {
      if (!document.hidden && (!sourceRef.current || sourceRef.current.readyState === 2)) open();
    };
    document.addEventListener("visibilitychange", onVis);
    return () => {
      document.removeEventListener("visibilitychange", onVis);
      sourceRef.current?.close();
    };
  }, []);

  return (
    <div className="min-h-screen bg-[radial-gradient(ellipse_at_top,_rgba(99,102,241,0.10),_transparent_50%),radial-gradient(ellipse_at_bottom_right,_rgba(244,63,94,0.07),_transparent_55%)] bg-background">
      <Header
        connected={connected}
        eventCount={eventCount}
        lastTimestamp={latest?.timestampMs}
      />
      <main className="mx-auto max-w-[1600px] px-6 py-6 space-y-4">
        {/* Row 1 — Sensors | Classification | Accuracy */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <SensorPanel
            sensorValues={latest?.sensorValues}
            maxTemp={latest?.maxTemp}
            avgTemp={latest?.avgTemp}
          />
          <ClassificationPanel
            label={latest?.classificationLabel}
            confidence={latest?.classificationConfidence}
          />
          <AccuracyPanel events={log} />
        </div>

        {/* Row 2 — Escalation | Sensor history (2 cols) */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <EscalationPanel
            tier={latest?.escalationTier}
            action={latest?.escalationAction}
            history={escalations}
          />
          <div className="lg:col-span-2">
            <SensorHistoryChart data={history} />
          </div>
        </div>

        {/* Row 3 — LLM full width */}
        <LLMPanel
          analysis={latest?.llmAnalysis}
          recommendation={latest?.llmRecommendation}
          similar={latest?.similarIncidents}
        />

        {/* Row 4 — Chat | Event log (+ Fault when ?debug=1) */}
        <div
          className={
            debug
              ? "grid grid-cols-1 lg:grid-cols-3 gap-4"
              : "grid grid-cols-1 lg:grid-cols-2 gap-4"
          }
        >
          {debug && <FaultPanel />}
          <ChatPanel />
          <EventLog events={log} />
        </div>
      </main>
    </div>
  );
}
