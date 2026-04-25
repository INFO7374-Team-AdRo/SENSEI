// safety-dashboard/app/page.tsx
"use client";
import React, { useEffect, useState, useRef } from "react";
import { fetchEvents, SensorHistoryPoint } from "./lib/api";
import {
  AlertTriangle, Thermometer, Wind, Activity,
  Camera, MapPin, Bot, Droplets, Flame, HardHat,
  ShieldAlert, ShieldCheck, ShieldX, CircleX, Volume2,
} from "lucide-react";
import {
  LineChart, Line, XAxis, YAxis, Tooltip, Legend,
  ResponsiveContainer,
} from "recharts";

const API_BASE = "http://localhost:8080";

// ── Types ─────────────────────────────────────────────────────────────────────
interface SensorEvent {
  MQ2?: number; MQ3?: number; MQ5?: number; MQ6?: number;
  MQ7?: number; MQ8?: number; MQ135?: number;
  confidence?: number; timestamp?: string;
}
interface EscalationEvent {
  hazardType: string; severity: string; confidence: number;
  recommendation: string; timestamp: string;
}
interface InspectionEvent {
  waypointId: string; robotId: string; location: string;
  environment: string; anomalyType: string; anomalyCode: string;
  safetyGrade: string; severity: string; description: string;
  imageUrl: string | null; audioUrl: string | null; infraredUrl: string | null;
  coReading: number | null; ch4Level: number | null;
  o2Level: number | null; h2sReading: number | null;
  temperature: number | null; humidity: number | null; soundLevel: number | null;
  timestamp: string;
}
interface SystemStatus {
  sensorShards: number; recentSensorEvents: number;
  recentEscalationEvents: number; totalSensorEvents: number;
  totalEscalationEvents: number; totalIncidents: number;
  latestClassification: string | null; clusterNode: string;
}

// ── MQ sensor config (ADC drops when gas detected) ────────────────────────────
const MQ_THRESHOLDS: Record<string, number> = {
  MQ2: 690, MQ3: 490, MQ5: 400, MQ6: 400, MQ7: 560, MQ8: 580, MQ135: 430,
};
const MQ_BASELINES: Record<string, number> = {
  MQ2: 748, MQ3: 529, MQ5: 431, MQ6: 425, MQ7: 606, MQ8: 637, MQ135: 474,
};
const MQ_LABELS: Record<string, string> = {
  MQ2: "Flammable", MQ3: "Alcohol", MQ5: "LPG", MQ6: "Butane",
  MQ7: "CO", MQ8: "Hydrogen", MQ135: "Air",
};

// ── Environmental sensor config (value rises when gas detected) ───────────────
// pct  = how full the bar is (0–100)
// line = where the threshold dashed line sits (0–100 %)
const ENV_SENSORS = [
  {
    key: "coReading" as keyof InspectionEvent,
    label: "CO", unit: "ppm",
    pct: (v: number) => Math.min(100, (v / 50) * 100),
    linePct: (10 / 50) * 100,
    breach: (v: number) => v > 10,
    fmt: (v: number) => v.toFixed(1),
  },
  {
    key: "ch4Level" as keyof InspectionEvent,
    label: "CH₄", unit: "%",
    pct: (v: number) => Math.min(100, (v / 1) * 100),
    linePct: (0.1 / 1) * 100,
    breach: (v: number) => v > 0.1,
    fmt: (v: number) => v.toFixed(3),
  },
  {
    key: "o2Level" as keyof InspectionEvent,
    label: "O₂", unit: "%",
    pct: (v: number) => Math.min(100, Math.max(0, ((v - 15) / 10) * 100)),
    linePct: ((19.5 - 15) / 10) * 100,
    breach: (v: number) => v < 19.5 || v > 23,
    fmt: (v: number) => v.toFixed(1),
  },
  {
    key: "h2sReading" as keyof InspectionEvent,
    label: "H₂S", unit: "ppm",
    pct: (v: number) => Math.min(100, (v / 10) * 100),
    linePct: (1 / 10) * 100,
    breach: (v: number) => v > 1,
    fmt: (v: number) => v.toFixed(1),
  },
  {
    key: "temperature" as keyof InspectionEvent,
    label: "Temp", unit: "°C",
    pct: (v: number) => Math.min(100, (v / 60) * 100),
    linePct: (40 / 60) * 100,
    breach: (v: number) => v > 40,
    fmt: (v: number) => v.toFixed(1),
  },
  {
    key: "humidity" as keyof InspectionEvent,
    label: "Hum", unit: "%RH",
    pct: (v: number) => Math.min(100, v),
    linePct: 80,
    breach: (v: number) => v > 80,
    fmt: (v: number) => v.toFixed(0),
  },
] as const;

// ── Grade / severity config ───────────────────────────────────────────────────
const GRADE_STYLE: Record<string, { card: string; badge: string; dot: string }> = {
  Critical:    { card: "border-red-700/50",    badge: "bg-red-700/30 text-red-300 border-red-600/50",          dot: "bg-red-500"    },
  Significant: { card: "border-orange-600/40", badge: "bg-orange-500/20 text-orange-300 border-orange-500/40", dot: "bg-orange-500" },
  Minor:       { card: "border-yellow-600/30", badge: "bg-yellow-500/20 text-yellow-300 border-yellow-500/30", dot: "bg-yellow-500" },
  Normal:      { card: "border-green-700/30",  badge: "bg-green-500/20 text-green-300 border-green-500/30",    dot: "bg-green-500"  },
};
const TIER_COLORS: Record<string, string> = {
  low:      "border-yellow-500/30 bg-yellow-500/10 text-yellow-400",
  medium:   "border-orange-500/30 bg-orange-500/10 text-orange-400",
  high:     "border-red-500/30 bg-red-500/10 text-red-400",
  critical: "border-red-600/50 bg-red-700/20 text-red-300",
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function visualIcon(code: string, size = 11) {
  if (code === "fire")          return <Flame         size={size} className="text-red-400" />;
  if (code === "fall")          return <CircleX       size={size} className="text-red-400" />;
  if (code === "head")          return <HardHat       size={size} className="text-orange-400" />;
  if (code === "nonmask")       return <ShieldAlert   size={size} className="text-orange-400" />;
  if (code === "cigarette")     return <Flame         size={size} className="text-yellow-400" />;
  if (code === "nonautomobile") return <AlertTriangle size={size} className="text-yellow-400" />;
  return <Camera size={size} className="text-gray-400" />;
}
function gradeIcon(sev: string, size = 13) {
  if (sev === "Critical")    return <ShieldX     size={size} className="text-red-400" />;
  if (sev === "Significant") return <ShieldAlert size={size} className="text-orange-400" />;
  if (sev === "Minor")       return <ShieldAlert size={size} className="text-yellow-400" />;
  return                            <ShieldCheck size={size} className="text-green-400" />;
}
function relTime(iso: string) {
  const s = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60)   return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}

type LiveEvent =
  | { kind: "escalation"; ts: number; data: EscalationEvent }
  | { kind: "visual";     ts: number; data: InspectionEvent };

// ── Dashboard ─────────────────────────────────────────────────────────────────
export default function Dashboard() {
  const [sensors,    setSensors]    = useState<SensorEvent[]>([]);
  const [escalations, setEscalations] = useState<EscalationEvent[]>([]);
  const [status,     setStatus]     = useState<SystemStatus | null>(null);
  const [thermal,    setThermal]    = useState<{
    temperature: number; anomaly: boolean;
    currentFrameUrl?: string | null; currentLabel?: string | null;
  } | null>(null);
  const [sensorHistory,   setSensorHistory]   = useState<SensorHistoryPoint[]>([]);
  const [visualEvents,    setVisualEvents]    = useState<InspectionEvent[]>([]);
  const feedRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const tick = async () => {
      try {
        const data = await fetchEvents();
        if (!data) return;
        if (data.latestSensor) setSensors([data.latestSensor as never]);
        setEscalations(data.escalations ?? []);
        setVisualEvents((data.inspections ?? []) as InspectionEvent[]);
        if (data.thermal) setThermal(data.thermal);
        if (data.status)  setStatus(data.status as never);
        if (data.sensorHistory) setSensorHistory(data.sensorHistory);
      } catch { /* retry next tick */ }
    };
    tick();
    const id = setInterval(tick, 2000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    if (feedRef.current) feedRef.current.scrollTop = feedRef.current.scrollHeight;
  }, [escalations, visualEvents]);

  const latestSensor = sensors[sensors.length - 1] ?? null;

  // Latest visual event (any grade) — for cameras and bar values
  const latestVisual = visualEvents[0] ?? null;

  // Environmental history — newest-first array reversed to chronological
  const envHistory = [...visualEvents].reverse().map(e => ({
    ts: e.timestamp,
    co:   e.coReading   ?? 0,
    h2s:  e.h2sReading  ?? 0,
    temp: e.temperature ?? 0,
    o2:   e.o2Level     ?? 0,
  }));

  // Unified alert feed — Normal visual events excluded (compliant, not alerts)
  const liveFeed: LiveEvent[] = [
    ...escalations.map(e => ({ kind: "escalation" as const, ts: new Date(e.timestamp || 0).getTime(), data: e })),
    ...visualEvents
      .filter(e => e.severity !== "Normal")
      .map(e => ({ kind: "visual" as const, ts: new Date(e.timestamp || 0).getTime(), data: e })),
  ].sort((a, b) => b.ts - a.ts).slice(0, 30);

  const visAlerts = visualEvents.filter(e => e.severity === "Critical" || e.severity === "Significant").length;

  return (
    <div className="space-y-6">

      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Safety Dashboard</h1>
          <p className="text-sm text-gray-500">Real-time industrial monitoring · gas · thermal · visual</p>
        </div>
        <div className="flex items-center gap-3">
          <div className={`px-3 py-1.5 rounded-full text-xs font-medium ${
            status ? "bg-green-500/20 text-green-400" : "bg-red-500/20 text-red-400"
          }`}>
            {status ? "System Online" : "Connecting…"}
          </div>
          {status && (
            <span className="text-xs text-gray-500">
              {(status.totalSensorEvents ?? 0).toLocaleString()} events · {status.sensorShards} shards
            </span>
          )}
        </div>
      </div>

      {/* ── Top row: cameras + status ── */}
      <div className="grid grid-cols-6 gap-4">

        {/* Thermal / IR camera — gas thermal image OR visual IR video */}
        <div className="col-span-2 bg-gray-900 rounded-xl border border-gray-800 overflow-hidden flex flex-col">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-gray-800">
            <div className="flex items-center gap-2 text-xs font-medium text-gray-300">
              <Thermometer size={13} className="text-orange-400" />
              Thermal / IR Camera
            </div>
            {thermal && (
              <div className={`flex items-center gap-1.5 text-xs px-2 py-1 rounded-full border ${
                thermal.anomaly
                  ? "bg-red-500/15 border-red-500/30 text-red-400"
                  : "bg-blue-500/15 border-blue-500/30 text-blue-400"
              }`}>
                <span className="font-bold font-mono">{thermal.temperature.toFixed(1)}°C</span>
                {thermal.anomaly && <span className="text-red-300 text-[10px] font-semibold">ANOMALY</span>}
              </div>
            )}
          </div>
          <div className="flex-1 bg-gray-950 flex items-center justify-center relative" style={{ height: 200 }}>
            {/* IR video from visual sensor — placeholder when none available */}
            {latestVisual?.infraredUrl ? (
              // eslint-disable-next-line jsx-a11y/media-has-caption
              <video
                src={`${API_BASE}${latestVisual.infraredUrl}`}
                autoPlay muted loop playsInline
                className="w-full h-full object-contain"
              />
            ) : (
              <div className="text-center text-gray-600">
                <Thermometer size={40} className="mx-auto mb-2 opacity-20" />
                <p className="text-xs">Waiting for IR feed…</p>
                {thermal && (
                  <p className={`text-xs mt-2 font-mono ${thermal.anomaly ? "text-red-400" : "text-blue-400"}`}>
                    {thermal.temperature.toFixed(1)}°C{thermal.anomaly ? " · ANOMALY" : " · Normal"}
                  </p>
                )}
              </div>
            )}
            {latestVisual?.infraredUrl && (
              <span className="absolute top-2 right-2 text-[8px] font-bold px-1 py-0.5 rounded bg-orange-500/30 text-orange-300 border border-orange-500/40">IR</span>
            )}
          </div>
        </div>

        {/* Visual camera — visible-light image from latest visual event */}
        {(() => {
          const g = latestVisual ? (GRADE_STYLE[latestVisual.severity] ?? GRADE_STYLE.Normal) : null;
          return (
            <div className="col-span-2 bg-gray-900 rounded-xl border border-gray-800 overflow-hidden flex flex-col">
              <div className="flex items-center justify-between px-4 py-2.5 border-b border-gray-800">
                <div className="flex items-center gap-2 text-xs font-medium text-gray-300">
                  <Camera size={13} className="text-purple-400" />
                  Visual Camera
                </div>
                {latestVisual && g && (
                  <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded border ${g.badge}`}>
                    {latestVisual.safetyGrade}
                  </span>
                )}
              </div>
              <div className="flex-1 bg-gray-950 flex items-center justify-center relative" style={{ height: 200 }}>
                {latestVisual?.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    key={latestVisual.waypointId}
                    src={`${API_BASE}${latestVisual.imageUrl}`}
                    className="w-full h-full object-contain"
                    alt={latestVisual.anomalyType}
                  />
                ) : (
                  <div className="text-center text-gray-600 px-3">
                    <Camera size={40} className="mx-auto mb-2 opacity-20" />
                    <p className="text-xs text-gray-600">No visual feed</p>
                  </div>
                )}
                {latestVisual && g && (
                  <span className={`absolute top-2 left-2 w-2 h-2 rounded-full ${g.dot}`} />
                )}
              </div>
            </div>
          );
        })()}

        {/* Status cards 2×2 */}
        <div className="col-span-2 grid grid-cols-2 gap-3">
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Activity size={13} />Sensor Events</div>
            <div className="text-2xl font-bold text-green-400">{(status?.totalSensorEvents ?? 0).toLocaleString()}</div>
            <div className="text-xs text-gray-500">Total processed</div>
          </div>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><AlertTriangle size={13} />Alerts (T2/T3)</div>
            <div className="text-2xl font-bold text-red-400">{status?.totalEscalationEvents ?? escalations.length}</div>
            <div className="text-xs text-gray-500">{status?.totalIncidents != null ? `${status.totalIncidents} incidents` : "alerts"}</div>
          </div>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Camera size={13} />Camera Events</div>
            <div className="text-2xl font-bold text-purple-400">{visualEvents.length}</div>
            <div className="text-xs text-gray-500">{visAlerts} alerts</div>
          </div>
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Wind size={13} />Classification</div>
            <div className={`text-lg font-bold ${
              status?.latestClassification === "Smoke"   ? "text-red-400"    :
              status?.latestClassification === "Mixture" ? "text-orange-400" :
              status?.latestClassification === "NoGas"   ? "text-green-400"  :
              status?.latestClassification === "Perfume" ? "text-yellow-400" :
              "text-gray-500"
            }`}>{status?.latestClassification ?? "—"}</div>
            <div className="text-xs text-gray-500">
              {latestSensor?.confidence ? `${(latestSensor.confidence * 100).toFixed(1)}% conf.` : "Awaiting"}
            </div>
          </div>
        </div>
      </div>

      {/* ── Unified sensor bar section ─────────────────────────────────────────
          Left group: MQ2–MQ135 (ADC drops when gas detected)
          Right group: CO/CH₄/O₂/H₂S/Temp/Hum (value rises when hazard detected)
          One dataset — whichever channel fired has values, the other shows zero. */}
      <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
        <h2 className="text-sm font-medium mb-4 flex items-center gap-2">
          <Activity size={16} className="text-blue-400" />
          Live Sensor Readings
        </h2>
        <div className="grid grid-cols-2 gap-4">

          {/* MQ gas sensors — left half */}
          <div className="flex justify-evenly">
            {Object.keys(MQ_THRESHOLDS).map(sensor => {
              const value    = (latestSensor?.[sensor as keyof SensorEvent] as number) || 0;
              const baseline = MQ_BASELINES[sensor];
              const thresh   = MQ_THRESHOLDS[sensor];
              const gasRange = baseline - (thresh - 60);
              const gasPct   = value > 0 ? Math.min(100, Math.max(0, ((baseline - value) / gasRange) * 100)) : 0;
              const linePct  = Math.min(100, ((baseline - thresh) / gasRange) * 100);
              const breached = value > 0 && value < thresh;
              return (
                <div key={sensor} className="text-center">
                  <div className="text-xs text-gray-500 mb-0.5">{sensor}</div>
                  <div className="text-[10px] text-gray-600 mb-2">{MQ_LABELS[sensor]}</div>
                  <div className="h-32 bg-gray-800 rounded-lg relative overflow-hidden mx-auto w-10">
                    <div className="absolute w-full border-t border-dashed border-red-500/50 z-10" style={{ bottom: `${linePct}%` }} />
                    <div
                      className={`absolute bottom-0 w-full transition-all duration-500 rounded-b-lg ${
                        breached ? "bg-gradient-to-t from-red-600 to-red-400" : "bg-gradient-to-t from-blue-600 to-blue-400"
                      }`}
                      style={{ height: `${gasPct}%` }}
                    />
                  </div>
                  <div className={`text-xs mt-2 font-mono ${breached ? "text-red-400" : "text-gray-400"}`}>{value || "—"}</div>
                  <div className="text-[9px] text-gray-600">ADC</div>
                </div>
              );
            })}
          </div>

          {/* Environmental sensors — right half, separated by left border */}
          <div className="flex justify-evenly border-l border-gray-700/60 pl-4">
            {ENV_SENSORS.map(cfg => {
              const raw     = latestVisual?.[cfg.key] as number | null ?? null;
              const value   = raw ?? 0;
              const fillPct = raw != null ? cfg.pct(value) : 0;
              const breached = raw != null && cfg.breach(value);
              return (
                <div key={cfg.key} className="text-center">
                  <div className="text-xs text-gray-500 mb-0.5">{cfg.label}</div>
                  <div className="text-[10px] text-gray-600 mb-2">{cfg.unit}</div>
                  <div className="h-32 bg-gray-800 rounded-lg relative overflow-hidden mx-auto w-10">
                    <div className="absolute w-full border-t border-dashed border-red-500/50 z-10" style={{ bottom: `${cfg.linePct}%` }} />
                    <div
                      className={`absolute bottom-0 w-full transition-all duration-500 rounded-b-lg ${
                        raw == null
                          ? "bg-gray-700/40"
                          : breached
                            ? "bg-gradient-to-t from-red-600 to-red-400"
                            : "bg-gradient-to-t from-teal-600 to-teal-400"
                      }`}
                      style={{ height: `${fillPct}%` }}
                    />
                  </div>
                  <div className={`text-xs mt-2 font-mono ${breached ? "text-red-400" : raw != null ? "text-gray-400" : "text-gray-700"}`}>
                    {raw != null ? cfg.fmt(value) : "—"}
                  </div>
                  <div className="text-[9px] text-gray-600">{cfg.unit}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* ── Trend charts — side by side ─────────────────────────────────────── */}
      {(sensorHistory.length > 1 || envHistory.length > 1) && (
        <div className="grid grid-cols-2 gap-4">

          {/* MQ sensor trend */}
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <h2 className="text-sm font-medium mb-3 flex items-center gap-2">
              <Activity size={15} className="text-blue-400" />
              MQ Gas Trend
              <span className="text-[10px] text-gray-600 font-normal">ADC — last {sensorHistory.length}</span>
            </h2>
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={sensorHistory} margin={{ top: 4, right: 4, left: -22, bottom: 0 }}>
                <XAxis dataKey="timestamp" tick={false} stroke="#374151" />
                <YAxis domain={[250, 800]} stroke="#374151" tick={{ fontSize: 10, fill: "#6b7280" }} />
                <Tooltip
                  contentStyle={{ background: "#111827", border: "1px solid #374151", borderRadius: 8, fontSize: 11 }}
                  labelFormatter={() => ""}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  formatter={(val: any, name: any) => [val, name]}
                />
                <Legend wrapperStyle={{ fontSize: 9, paddingTop: 6 }} />
                <Line type="monotone" dataKey="MQ2"   stroke="#ef4444" dot={false} strokeWidth={1.5} name="MQ2" />
                <Line type="monotone" dataKey="MQ3"   stroke="#f97316" dot={false} strokeWidth={1.5} name="MQ3" />
                <Line type="monotone" dataKey="MQ5"   stroke="#eab308" dot={false} strokeWidth={1.5} name="MQ5" />
                <Line type="monotone" dataKey="MQ6"   stroke="#22c55e" dot={false} strokeWidth={1.5} name="MQ6" />
                <Line type="monotone" dataKey="MQ7"   stroke="#06b6d4" dot={false} strokeWidth={1.5} name="MQ7" />
                <Line type="monotone" dataKey="MQ8"   stroke="#8b5cf6" dot={false} strokeWidth={1.5} name="MQ8" />
                <Line type="monotone" dataKey="MQ135" stroke="#ec4899" dot={false} strokeWidth={1.5} name="MQ135" />
              </LineChart>
            </ResponsiveContainer>
          </div>

          {/* Environmental sensor trend */}
          <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
            <h2 className="text-sm font-medium mb-3 flex items-center gap-2">
              <Activity size={15} className="text-teal-400" />
              Environmental Trend
              <span className="text-[10px] text-gray-600 font-normal">last {envHistory.length} readings</span>
            </h2>
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={envHistory} margin={{ top: 4, right: 28, left: -22, bottom: 0 }}>
                <XAxis dataKey="ts" tick={false} stroke="#374151" />
                <YAxis yAxisId="left"  domain={[0, 100]}  stroke="#374151" tick={{ fontSize: 10, fill: "#6b7280" }} />
                <YAxis yAxisId="right" domain={[15, 25]}  orientation="right" stroke="#374151" tick={{ fontSize: 10, fill: "#6b7280" }} />
                <Tooltip
                  contentStyle={{ background: "#111827", border: "1px solid #374151", borderRadius: 8, fontSize: 11 }}
                  labelFormatter={() => ""}
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  formatter={(val: any, name: any) => [val, name]}
                />
                <Legend wrapperStyle={{ fontSize: 9, paddingTop: 6 }} />
                <Line yAxisId="left"  type="monotone" dataKey="co"   stroke="#ef4444" dot={false} strokeWidth={1.5} name="CO (ppm)" />
                <Line yAxisId="left"  type="monotone" dataKey="h2s"  stroke="#f97316" dot={false} strokeWidth={1.5} name="H₂S (ppm)" />
                <Line yAxisId="left"  type="monotone" dataKey="temp" stroke="#06b6d4" dot={false} strokeWidth={1.5} name="Temp (°C)" />
                <Line yAxisId="right" type="monotone" dataKey="o2"   stroke="#22c55e" dot={false} strokeWidth={1.5} name="O₂ (%)" />
              </LineChart>
            </ResponsiveContainer>
          </div>

        </div>
      )}

      {/* ── Unified live alert feed ─────────────────────────────────────────── */}
      <div className="bg-gray-900 rounded-xl border border-gray-800">
        <div className="px-4 py-3 border-b border-gray-800">
          <h2 className="text-sm font-medium flex items-center gap-2">
            <AlertTriangle size={16} className="text-red-400" />
            Live Alert Feed
          </h2>
        </div>

        <div ref={feedRef} className="divide-y divide-gray-800/60 max-h-[32rem] overflow-y-auto">
          {liveFeed.length === 0 ? (
            <div className="text-center text-gray-600 py-12 text-sm">No alerts yet</div>
          ) : liveFeed.map((ev, i) => {

            /* Gas escalation row */
            if (ev.kind === "escalation") {
              const e = ev.data;
              const tk = e.severity === "critical" ? "critical" : e.severity === "high" ? "high" : e.severity === "medium" ? "medium" : "low";
              return (
                <div key={i} className={`flex items-start gap-3 px-4 py-3 border-l-2 ${TIER_COLORS[tk] ?? TIER_COLORS.medium}`}>
                  <div className="flex-shrink-0 w-16 h-14 rounded-lg bg-gray-800/60 border border-gray-700/60 flex flex-col items-center justify-center gap-0.5">
                    <AlertTriangle size={16} className="opacity-60" />
                    <Thermometer   size={10} className="text-orange-400 opacity-60" />
                    <span className="text-[7px] text-gray-600 uppercase tracking-wide">gas</span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-[10px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded border bg-black/20">{e.severity?.toUpperCase()}</span>
                      <span className="text-xs font-semibold">{e.hazardType}</span>
                      {e.confidence && <span className="text-[10px] text-gray-500 ml-auto">{(e.confidence * 100).toFixed(1)}% conf.</span>}
                    </div>
                    <p className="text-[11px] mt-1 opacity-80 line-clamp-1">{e.recommendation}</p>
                    <p className="text-[10px] text-gray-500 mt-0.5">{relTime(e.timestamp)}</p>
                  </div>
                </div>
              );
            }

            /* Visual sensor row */
            const e = ev.data;
            const g = GRADE_STYLE[e.severity] ?? GRADE_STYLE.Normal;
            return (
              <div key={i} className={`flex items-start gap-3 px-4 py-3 bg-gray-900/50 border-l-2 ${g.card}`}>
                {/* Thumbnail */}
                <div className="flex-shrink-0 w-16 h-14 rounded-lg overflow-hidden bg-gray-800 border border-gray-700 flex items-center justify-center">
                  {e.imageUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={`${API_BASE}${e.imageUrl}`} alt={e.anomalyType} className="w-full h-full object-cover" />
                  ) : (
                    <div className="flex flex-col items-center gap-0.5">
                      {visualIcon(e.anomalyCode, 16)}
                      <span className="text-[8px] text-gray-600">SIM</span>
                    </div>
                  )}
                </div>

                {/* Content */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`flex items-center gap-1 text-[10px] font-bold px-1.5 py-0.5 rounded border ${g.badge}`}>
                      {gradeIcon(e.severity, 9)}{e.safetyGrade}
                    </span>
                    <span className="flex items-center gap-1 text-[11px] font-semibold text-gray-200">
                      {visualIcon(e.anomalyCode, 10)}{e.anomalyType}
                    </span>
                    <span className="text-[10px] text-gray-500 ml-auto">{relTime(e.timestamp)}</span>
                  </div>
                  <div className="flex items-center gap-2 mt-0.5 flex-wrap">
                    <span className="flex items-center gap-1 text-[10px] text-gray-400"><MapPin size={9} />{e.location}</span>
                    <span className="text-[9px] px-1 py-0.5 rounded bg-gray-800 text-gray-500 border border-gray-700">{e.environment}</span>
                    <span className="flex items-center gap-1 text-[9px] text-gray-600"><Bot size={9} />{e.robotId}</span>
                  </div>
                  <p className="text-[10px] text-gray-500 mt-0.5 line-clamp-1">{e.description}</p>

                  {/* Sensor chips inline */}
                  {(e.coReading != null || e.ch4Level != null || e.o2Level != null ||
                    e.h2sReading != null || e.temperature != null || e.humidity != null) && (
                    <div className="flex items-center gap-2 mt-1 flex-wrap">
                      {e.coReading   != null && <span className={`flex items-center gap-1 text-[9px] ${e.coReading > 10 ? "text-red-400" : "text-gray-600"}`}><Wind size={8}/>CO {e.coReading.toFixed(1)}</span>}
                      {e.ch4Level    != null && <span className={`flex items-center gap-1 text-[9px] ${e.ch4Level > 0.1 ? "text-orange-400" : "text-gray-600"}`}><Flame size={8}/>CH₄ {e.ch4Level.toFixed(3)}%</span>}
                      {e.o2Level     != null && <span className={`flex items-center gap-1 text-[9px] ${(e.o2Level < 19.5 || e.o2Level > 23) ? "text-yellow-400" : "text-gray-600"}`}><Activity size={8}/>O₂ {e.o2Level.toFixed(1)}%</span>}
                      {e.h2sReading  != null && <span className={`flex items-center gap-1 text-[9px] ${e.h2sReading > 1 ? "text-red-400" : "text-gray-600"}`}><AlertTriangle size={8}/>H₂S {e.h2sReading.toFixed(1)}</span>}
                      {e.temperature != null && <span className="flex items-center gap-1 text-[9px] text-gray-600"><Thermometer size={8}/>{e.temperature.toFixed(1)}°C</span>}
                      {e.humidity    != null && <span className="flex items-center gap-1 text-[9px] text-gray-600"><Droplets size={8}/>{e.humidity.toFixed(0)}%RH</span>}
                      {e.soundLevel  != null && <span className="flex items-center gap-1 text-[9px] text-gray-600"><Volume2 size={8}/>{e.soundLevel.toFixed(0)}dB</span>}
                    </div>
                  )}

                  {e.audioUrl && (
                    <div className="mt-1.5">
                      {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
                      <audio
                        controls
                        src={`${API_BASE}${e.audioUrl}`}
                        className="h-6 w-full max-w-[220px] opacity-70 hover:opacity-100 transition-opacity"
                        style={{ filter: "invert(1) hue-rotate(180deg) brightness(0.7)" }}
                      />
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
