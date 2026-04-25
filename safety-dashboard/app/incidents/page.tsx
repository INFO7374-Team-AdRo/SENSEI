// safety-dashboard/app/incidents/page.tsx
"use client";
import { useEffect, useState } from "react";
import { fetchIncidents, fetchIncidentReport, IncidentEvent } from "../lib/api";
import { FileText, AlertTriangle, ChevronRight, Loader2, RefreshCw, Clock, Shield, Activity, Thermometer, Camera, MapPin, Bot, Wind, Droplets, ShieldX, ShieldAlert, ShieldCheck, Volume2 } from "lucide-react";

interface SensorReading { adc: number; breached: boolean; trend: string; }
interface ThermalData   { temperature: number; anomaly: boolean; }

interface IncidentReport {
  incidentId: string;
  summary: string;
  rootCause: string;
  affectedSensors: string[];
  sensorReadings?: Record<string, SensorReading>;
  thermalData?: ThermalData;
  timeline: { time: string; event: string }[];
  evidenceSteps?: string[];
  recommendation: string;
  generatedAt: string;
}

const API_BASE = "http://localhost:8080";

const SEVERITY_CONFIG: Record<string, { bg: string; text: string; border: string; label: string }> = {
  low:      { bg: "bg-yellow-500/10", text: "text-yellow-400", border: "border-yellow-500/30", label: "LOW" },
  medium:   { bg: "bg-orange-500/10", text: "text-orange-400", border: "border-orange-500/30", label: "MEDIUM" },
  high:     { bg: "bg-red-500/10",    text: "text-red-400",    border: "border-red-500/30",    label: "HIGH" },
  critical: { bg: "bg-red-700/20",    text: "text-red-300",    border: "border-red-600/40",    label: "CRITICAL" },
};

const INSPEC_GRADE_CONFIG: Record<string, { bg: string; text: string; border: string; label: string }> = {
  Critical:    { bg: "bg-red-700/20",    text: "text-red-300",    border: "border-red-600/40",    label: "GRADE 1" },
  Significant: { bg: "bg-orange-500/10", text: "text-orange-400", border: "border-orange-500/30", label: "GRADE 2" },
  Minor:       { bg: "bg-yellow-500/10", text: "text-yellow-400", border: "border-yellow-500/30", label: "GRADE 3" },
  Normal:      { bg: "bg-green-500/10",  text: "text-green-400",  border: "border-green-500/30",  label: "NORMAL"  },
};

function gradeIcon(sev: string) {
  if (sev === "Critical")    return <ShieldX     size={9} />;
  if (sev === "Significant") return <ShieldAlert size={9} />;
  if (sev === "Minor")       return <ShieldAlert size={9} />;
  return                            <ShieldCheck size={9} />;
}

const SENSOR_LABELS: Record<string, string> = {
  MQ2: "Flammable", MQ3: "Alcohol", MQ5: "LPG",
  MQ6: "Butane",    MQ7: "CO",      MQ8: "H₂", MQ135: "Air Quality",
};
const SENSOR_BASELINES: Record<string, number> = {
  MQ2: 748, MQ3: 529, MQ5: 431, MQ6: 425, MQ7: 606, MQ8: 637, MQ135: 474,
};
const SENSOR_THRESHOLDS: Record<string, number> = {
  MQ2: 690, MQ3: 490, MQ5: 400, MQ6: 400, MQ7: 560, MQ8: 580, MQ135: 430,
};

/** Friendly timestamp: "Apr 18, 2025 · 14:32" */
function formatTs(ts?: string | null): string {
  if (!ts) return "—";
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleString([], {
    month: "short", day: "numeric", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

/**
 * Guard against old MongoDB records where `summary` was accidentally stored as a raw
 * JSON string (e.g. the full LLM response blob).  If the value looks like JSON, try
 * to pull the "explanation" or "causalChain" field out of it; otherwise return as-is.
 */
function sanitizeSummary(raw?: string | null): string {
  if (!raw) return "No summary available.";
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{")) return trimmed;           // already plain text
  try {
    const obj = JSON.parse(trimmed);
    return obj.explanation || obj.causalChain || obj.recommendation || "See root cause for details.";
  } catch {
    return "See root cause for details.";
  }
}

export default function IncidentsPage() {
  const [incidents, setIncidents] = useState<IncidentEvent[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [report, setReport] = useState<IncidentReport | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);

  const load = async () => {
    setRefreshing(true);
    setFetchError(null);
    try {
      const data = await fetchIncidents();
      setIncidents([...data].reverse());
    } catch {
      setFetchError("Cannot reach backend at localhost:8080 — is the Java server running?");
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleSelect = async (inc: IncidentEvent) => {
    setSelectedId(inc.incidentId);
    setReport(null);
    setReportLoading(true);
    try {
      const data = await fetchIncidentReport(inc.incidentId);
      setReport(data);
    } catch {
      setReport(null);
    } finally {
      setReportLoading(false);
    }
  };

  const isInspec = (inc: IncidentEvent) => inc.source === "inspecsafe" || inc.tier === "INSPECSAFE";

  const counts = {
    total:    incidents.length,
    critical: incidents.filter((e) => e.tier === "T3_SHUTDOWN").length,
    high:     incidents.filter((e) => e.tier === "T2_ALERT").length,
    inspec:   incidents.filter(isInspec).length,
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 bg-orange-600/20 border border-orange-600/30 rounded-xl flex items-center justify-center">
            <FileText size={18} className="text-orange-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Incidents & Reports</h1>
            <p className="text-xs text-gray-500">LLM-generated post-incident analysis</p>
          </div>
        </div>
        <button
          onClick={load}
          disabled={refreshing}
          className="flex items-center gap-2 text-xs text-gray-400 hover:text-white bg-gray-800 hover:bg-gray-700 border border-gray-700 px-3 py-1.5 rounded-lg transition-colors"
        >
          <RefreshCw size={13} className={refreshing ? "animate-spin" : ""} />
          Refresh
        </button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-4 gap-4">
        <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
          <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Activity size={13} /> Total Incidents</div>
          <div className="text-2xl font-bold text-white">{counts.total}</div>
        </div>
        <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
          <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><AlertTriangle size={13} className="text-red-400" /> Critical (T3)</div>
          <div className="text-2xl font-bold text-red-400">{counts.critical}</div>
        </div>
        <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
          <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Shield size={13} className="text-orange-400" /> Alerts (T2)</div>
          <div className="text-2xl font-bold text-orange-400">{counts.high}</div>
        </div>
        <div className="bg-gray-900 rounded-xl border border-gray-800 p-4">
          <div className="flex items-center gap-2 text-xs text-gray-500 mb-2"><Camera size={13} className="text-purple-400" /> Visual (InspecSafe)</div>
          <div className="text-2xl font-bold text-purple-400">{counts.inspec}</div>
        </div>
      </div>

      {fetchError && (
        <div className="rounded-xl border border-red-600/40 bg-red-500/10 px-4 py-3 text-sm text-red-400 flex items-center gap-2">
          <AlertTriangle size={15} />
          {fetchError}
        </div>
      )}

      {/* Split: list + report */}
      <div className="grid grid-cols-5 gap-4">
        {/* Incident list */}
        <div className="col-span-2 bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-800 text-sm font-medium">
            Incident Log
          </div>
          <div className="overflow-y-auto max-h-[32rem]">
            {incidents.length === 0 ? (
              <div className="text-center text-gray-600 py-12 text-sm">No incidents recorded</div>
            ) : (
              incidents.map((inc) => {
                const inspecSafe = isInspec(inc);
                const isSelected = selectedId === inc.incidentId;

                // Badge config
                let cfg;
                if (inspecSafe) {
                  cfg = INSPEC_GRADE_CONFIG[inc.severity ?? "Significant"] ?? INSPEC_GRADE_CONFIG.Significant;
                } else {
                  const tierKey = inc.tier === "T3_SHUTDOWN" ? "critical" : inc.tier === "T2_ALERT" ? "high" : "low";
                  cfg = SEVERITY_CONFIG[tierKey];
                }

                return (
                  <button
                    key={inc.incidentId}
                    onClick={() => handleSelect(inc)}
                    className={`w-full text-left px-4 py-3 border-b border-gray-800/60 hover:bg-gray-800/60 transition-colors flex items-start gap-3 ${isSelected ? "bg-gray-800" : ""}`}
                  >
                    {/* Thumbnail for InspecSafe, badge icon for gas */}
                    {inspecSafe && inc.imageUrl ? (
                      <div className="flex-shrink-0 w-12 h-10 rounded-lg overflow-hidden bg-gray-800 border border-gray-700 mt-0.5">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img src={`${API_BASE}${inc.imageUrl}`} alt="" className="w-full h-full object-cover" />
                      </div>
                    ) : (
                      <div className={`mt-0.5 px-1.5 py-0.5 rounded text-[9px] font-bold border flex items-center gap-0.5 ${cfg.bg} ${cfg.text} ${cfg.border} flex-shrink-0`}>
                        {inspecSafe && gradeIcon(inc.severity ?? "")}
                        {cfg.label}
                      </div>
                    )}

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium text-white truncate">{inc.hazardType}</span>
                        {inspecSafe && (
                          <span className="text-[9px] px-1 py-0.5 rounded bg-purple-900/30 text-purple-400 border border-purple-800/40 flex-shrink-0">
                            <Camera size={8} className="inline mr-0.5" />Visual
                          </span>
                        )}
                      </div>
                      {inspecSafe && inc.location && (
                        <div className="flex items-center gap-1 text-[10px] text-gray-500 mt-0.5">
                          <MapPin size={8} />{inc.location}
                          {inc.environment && <span className="ml-1 text-gray-600">· {inc.environment}</span>}
                        </div>
                      )}
                      {!inspecSafe && (
                        <div className="text-[10px] text-gray-500 mt-0.5 truncate">{inc.recommendation}</div>
                      )}
                      <div className="flex items-center gap-2 mt-1">
                        <span className="flex items-center gap-1 text-[10px] text-gray-600">
                          <Clock size={9} />{formatTs(inc.timestamp)}
                        </span>
                        <span className="text-[9px] text-gray-700 font-mono">{inc.tier}</span>
                      </div>
                    </div>
                    <ChevronRight size={14} className={`text-gray-600 flex-shrink-0 mt-1 ${isSelected ? "text-gray-400" : ""}`} />
                  </button>
                );
              })
            )}
          </div>
        </div>

        {/* Report panel */}
        <div className="col-span-3 bg-gray-900 rounded-xl border border-gray-800 overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-800 text-sm font-medium flex items-center gap-2">
            <FileText size={14} className="text-orange-400" />
            Post-Incident Report
            {selectedId && (
              <span className="ml-auto text-[10px] text-gray-600 font-mono">{selectedId}</span>
            )}
          </div>
          <div className="p-4 overflow-y-auto max-h-[32rem]">
            {!selectedId && (
              <div className="text-center text-gray-600 py-16 text-sm">
                Select an incident to view the LLM-generated report
              </div>
            )}
            {selectedId && reportLoading && (
              <div className="flex items-center justify-center gap-2 py-16 text-gray-500 text-sm">
                <Loader2 size={16} className="animate-spin" />
                Loading report…
              </div>
            )}
            {selectedId && !reportLoading && !report && (
              <div className="text-center text-gray-600 py-16 text-sm">
                Report not available for this incident.
              </div>
            )}
            {report && (() => {
              // Detect type — InspecSafe or gas — to drive unified layout
              const r = report as unknown as {
                imageUrl?: string; audioUrl?: string; infraredUrl?: string;
                tier?: string; source?: string;
                location?: string; environment?: string; robotId?: string;
                anomalyType?: string; safetyGrade?: string; hazardType?: string;
                coReading?: number; ch4Level?: number; o2Level?: number; h2sReading?: number;
                temperature?: number; humidity?: number; soundLevel?: number;
              };
              const isInspecSafe = r.source === "inspecsafe" || r.tier === "INSPECSAFE";

              // Thermal temperature: from thermalData (gas) or sensor file (inspecsafe)
              const thermalTemp: number | null =
                report.thermalData?.temperature ?? r.temperature ?? null;
              const thermalAnomaly = report.thermalData?.anomaly ?? false;

              return (
              <div className="space-y-5">

                {/* ── Unified visual block: Inspection frame + Thermal ── */}
                <div className="grid grid-cols-2 gap-3">
                  {/* Left: Inspection camera frame */}
                  <div className="rounded-xl overflow-hidden border border-gray-700 bg-gray-950 flex flex-col">
                    <div className="px-3 py-1.5 border-b border-gray-800 text-[10px] font-semibold text-gray-500 uppercase tracking-widest flex items-center gap-1.5">
                      <Camera size={10} className="text-purple-400" /> Inspection Frame
                    </div>
                    <div className="flex-1 flex items-center justify-center" style={{ minHeight: 140 }}>
                      {r.imageUrl ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img
                          src={`${API_BASE}${r.imageUrl}`}
                          alt="Inspection frame"
                          className="w-full h-full object-cover"
                          style={{ maxHeight: 160 }}
                        />
                      ) : (
                        <div className="text-center text-gray-700 p-4">
                          <Camera size={36} className="mx-auto mb-2 opacity-30" />
                          <p className="text-[10px] text-gray-600">
                            {isInspecSafe ? "No image available" : "No visual inspection data"}
                          </p>
                          {!isInspecSafe && r.hazardType && (
                            <p className="text-[10px] text-gray-500 mt-1 font-medium">{r.hazardType}</p>
                          )}
                        </div>
                      )}
                    </div>
                    {/* Audio player below image */}
                    {r.audioUrl && (
                      <div className="px-3 py-2 border-t border-gray-800 flex items-center gap-2">
                        <Volume2 size={11} className="text-purple-400 flex-shrink-0" />
                        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
                        <audio controls src={`${API_BASE}${r.audioUrl}`} className="flex-1 h-6" style={{ minWidth: 0 }} />
                      </div>
                    )}
                  </div>

                  {/* Right: IR video (InspecSafe) or Thermal camera (gas) */}
                  <div className="rounded-xl border border-gray-700 bg-gray-950 flex flex-col overflow-hidden">
                    <div className="px-3 py-1.5 border-b border-gray-800 text-[10px] font-semibold text-gray-500 uppercase tracking-widest flex items-center gap-1.5">
                      <Thermometer size={10} className="text-orange-400" />
                      {isInspecSafe ? (r.infraredUrl ? "Infrared Video" : "Waypoint Environment") : "Thermal Camera"}
                      {r.infraredUrl && (
                        <span className="ml-auto text-[8px] font-bold px-1 py-0.5 rounded bg-orange-500/20 text-orange-300 border border-orange-500/30">IR</span>
                      )}
                    </div>
                    {/* InspecSafe with infrared video */}
                    {isInspecSafe && r.infraredUrl ? (
                      <div className="flex-1 bg-black" style={{ minHeight: 140 }}>
                        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
                        <video
                          src={`${API_BASE}${r.infraredUrl}`}
                          autoPlay muted loop playsInline
                          className="w-full h-full object-contain"
                          style={{ minHeight: 140 }}
                        />
                      </div>
                    ) : (
                      <div className="flex-1 flex flex-col items-center justify-center gap-2 p-4" style={{ minHeight: 140 }}>
                        {thermalTemp != null ? (
                          <>
                            <div className={`text-3xl font-mono font-bold ${
                              thermalAnomaly ? "text-red-400" : isInspecSafe ? "text-blue-300" : "text-orange-300"
                            }`}>
                              {thermalTemp.toFixed(1)}°C
                            </div>
                            {thermalAnomaly && (
                              <span className="text-[10px] bg-red-500/20 border border-red-500/30 text-red-300 px-2 py-0.5 rounded font-semibold">
                                HEAT ANOMALY
                              </span>
                            )}
                            {/* Humidity if available */}
                            {r.humidity != null && (
                              <div className="flex items-center gap-1 text-[11px] text-gray-500">
                                <Droplets size={11} />{r.humidity.toFixed(0)}% RH
                              </div>
                            )}
                          </>
                        ) : (
                          <div className="text-center text-gray-700">
                            <Thermometer size={36} className="mx-auto mb-2 opacity-30" />
                            <p className="text-[10px] text-gray-600">No thermal data</p>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                {/* ── Meta chips: location / robot / tier (always shown when present) ── */}
                {(r.location || r.environment || r.robotId || r.anomalyType || r.safetyGrade) && (
                  <div className="flex flex-wrap gap-2 text-[11px] text-gray-400">
                    {r.location    && <span className="flex items-center gap-1 bg-gray-800 border border-gray-700 px-2 py-0.5 rounded-full"><MapPin size={9}/>{r.location}</span>}
                    {r.environment && <span className="bg-gray-800 border border-gray-700 px-2 py-0.5 rounded-full">{r.environment}</span>}
                    {r.robotId     && <span className="flex items-center gap-1 bg-gray-800 border border-gray-700 px-2 py-0.5 rounded-full"><Bot size={9}/>{r.robotId}</span>}
                    {r.anomalyType && <span className="flex items-center gap-1 bg-purple-900/20 border border-purple-800/30 text-purple-300 px-2 py-0.5 rounded-full">{r.anomalyType}</span>}
                    {r.safetyGrade && <span className="bg-gray-800 border border-gray-700 px-2 py-0.5 rounded-full">{r.safetyGrade}</span>}
                  </div>
                )}

                {/* ── Multi-gas chips (InspecSafe) ── */}
                {(r.coReading != null || r.ch4Level != null || r.o2Level != null || r.h2sReading != null || r.soundLevel != null) && (
                  <div>
                    <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2 flex items-center gap-2">
                      <Activity size={12} className="text-green-400" /> Multi-Gas & Environmental Sensors
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      {r.coReading  != null && (
                        <div className={`flex flex-col items-center py-2 rounded-lg border ${r.coReading > 10 ? "bg-red-900/20 border-red-800/40" : "bg-gray-800/60 border-gray-700/50"}`}>
                          <Wind size={14} className={r.coReading > 10 ? "text-red-400" : "text-gray-500"} />
                          <div className={`text-sm font-mono font-bold mt-1 ${r.coReading > 10 ? "text-red-400" : "text-gray-300"}`}>{r.coReading.toFixed(1)}</div>
                          <div className="text-[9px] text-gray-600">CO ppm</div>
                        </div>
                      )}
                      {r.ch4Level   != null && (
                        <div className={`flex flex-col items-center py-2 rounded-lg border ${r.ch4Level > 0.1 ? "bg-orange-900/20 border-orange-800/40" : "bg-gray-800/60 border-gray-700/50"}`}>
                          <Wind size={14} className={r.ch4Level > 0.1 ? "text-orange-400" : "text-gray-500"} />
                          <div className={`text-sm font-mono font-bold mt-1 ${r.ch4Level > 0.1 ? "text-orange-400" : "text-gray-300"}`}>{r.ch4Level.toFixed(3)}</div>
                          <div className="text-[9px] text-gray-600">CH₄ %VOL</div>
                        </div>
                      )}
                      {r.o2Level    != null && (
                        <div className={`flex flex-col items-center py-2 rounded-lg border ${(r.o2Level < 19.5 || r.o2Level > 23) ? "bg-yellow-900/20 border-yellow-800/40" : "bg-gray-800/60 border-gray-700/50"}`}>
                          <Activity size={14} className={(r.o2Level < 19.5 || r.o2Level > 23) ? "text-yellow-400" : "text-blue-400"} />
                          <div className={`text-sm font-mono font-bold mt-1 ${(r.o2Level < 19.5 || r.o2Level > 23) ? "text-yellow-400" : "text-blue-300"}`}>{r.o2Level.toFixed(1)}</div>
                          <div className="text-[9px] text-gray-600">O₂ %VOL</div>
                        </div>
                      )}
                      {r.h2sReading != null && (
                        <div className={`flex flex-col items-center py-2 rounded-lg border ${r.h2sReading > 1 ? "bg-red-900/20 border-red-800/40" : "bg-gray-800/60 border-gray-700/50"}`}>
                          <AlertTriangle size={14} className={r.h2sReading > 1 ? "text-red-400" : "text-gray-500"} />
                          <div className={`text-sm font-mono font-bold mt-1 ${r.h2sReading > 1 ? "text-red-400" : "text-gray-300"}`}>{r.h2sReading.toFixed(1)}</div>
                          <div className="text-[9px] text-gray-600">H₂S ppm</div>
                        </div>
                      )}
                      {r.soundLevel != null && (
                        <div className="flex flex-col items-center py-2 rounded-lg border bg-gray-800/60 border-gray-700/50">
                          <Volume2 size={14} className="text-gray-500" />
                          <div className="text-sm font-mono font-bold mt-1 text-gray-300">{r.soundLevel.toFixed(0)}</div>
                          <div className="text-[9px] text-gray-600">dB SPL</div>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {/* ── MQ gas sensor bars (gas incidents) ── */}
                {report.sensorReadings && Object.keys(report.sensorReadings).length > 0 && (
                  <div>
                    <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3 flex items-center gap-2">
                      <Activity size={12} className="text-blue-400" /> MQ Gas Sensor Array
                    </div>
                    <div className="grid grid-cols-7 gap-2">
                      {Object.entries(report.sensorReadings).map(([sensor, reading]) => {
                        const baseline  = SENSOR_BASELINES[sensor]  ?? 700;
                        const threshold = SENSOR_THRESHOLDS[sensor] ?? 500;
                        const gasRange  = baseline - (threshold - 60);
                        const gasPct    = reading.adc > 0
                          ? Math.min(100, Math.max(0, ((baseline - reading.adc) / gasRange) * 100))
                          : 0;
                        const threshPct = Math.min(100, ((baseline - threshold) / gasRange) * 100);
                        return (
                          <div key={sensor} className="text-center">
                            <div className={`text-[10px] font-semibold mb-0.5 ${reading.breached ? "text-red-400" : "text-gray-500"}`}>{sensor}</div>
                            <div className="text-[9px] text-gray-600 mb-1">{SENSOR_LABELS[sensor]}</div>
                            <div className="h-20 bg-gray-800 rounded relative overflow-hidden mx-auto w-7">
                              <div className="absolute w-full border-t border-dashed border-red-500/50 z-10" style={{ bottom: `${threshPct}%` }} />
                              <div className={`absolute bottom-0 w-full rounded-b ${reading.breached ? "bg-gradient-to-t from-red-600 to-red-400" : "bg-gradient-to-t from-blue-700 to-blue-500"}`}
                                style={{ height: `${gasPct}%` }} />
                            </div>
                            <div className={`text-[9px] mt-1 font-mono ${reading.breached ? "text-red-400" : "text-gray-500"}`}>{reading.adc}</div>
                            {reading.breached && <div className="text-[8px] text-red-500 leading-none">BREACH</div>}
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* Summary */}
                <div>
                  <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2">Summary</div>
                  <p className="text-sm text-gray-300 leading-relaxed">{sanitizeSummary(report.summary)}</p>
                </div>

                {/* Root cause */}
                <div>
                  <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2">Root Cause</div>
                  <div className="bg-red-900/10 border border-red-800/30 rounded-lg px-4 py-3 text-sm text-red-300">
                    {report.rootCause}
                  </div>
                </div>

                {/* Timeline */}
                {report.timeline?.length > 0 && (
                  <div>
                    <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2">Timeline</div>
                    <div className="space-y-2">
                      {report.timeline.map((t, i) => (
                        <div key={i} className="flex gap-3 text-xs">
                          <span className="text-gray-600 font-mono flex-shrink-0 w-14 pt-0.5">{t.time}</span>
                          <div className="flex gap-2 items-start">
                            <div className="w-1.5 h-1.5 bg-orange-500 rounded-full mt-1.5 flex-shrink-0" />
                            <span className="text-gray-300">{t.event}</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Evidence Chain + one RAG match (first/best) */}
                {report.evidenceSteps && report.evidenceSteps.length > 0 && (() => {
                  const normalSteps = report.evidenceSteps.filter(s => !s.startsWith("Similar past incident"));
                  const ragMatch    = report.evidenceSteps.find(s =>  s.startsWith("Similar past incident"));
                  const displayed   = ragMatch ? [...normalSteps, ragMatch] : normalSteps;
                  if (displayed.length === 0) return null;
                  return (
                    <div>
                      <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2">
                        Evidence Chain
                      </div>
                      <div className="space-y-1.5">
                        {displayed.map((step, i) => {
                          const isSimilar = step.startsWith("Similar past incident");
                          return (
                            <div key={i} className={`flex gap-2 text-xs rounded-lg px-3 py-2 ${
                              isSimilar
                                ? "bg-purple-900/20 border border-purple-800/30 text-purple-300"
                                : "bg-gray-800/60 text-gray-300"
                            }`}>
                              <span className={`font-mono flex-shrink-0 ${isSimilar ? "text-purple-500" : "text-gray-600"}`}>
                                {isSimilar ? "RAG" : String(i + 1).padStart(2, "0")}
                              </span>
                              <span>{step}</span>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  );
                })()}

                {/* Recommendation */}
                <div>
                  <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-2">Recommendation</div>
                  <div className="bg-green-900/10 border border-green-800/30 rounded-lg px-4 py-3 text-sm text-green-300">
                    {report.recommendation}
                  </div>
                </div>

                <div className="text-[10px] text-gray-600 pt-2 border-t border-gray-800">
                  Generated {formatTs(report.generatedAt)}
                </div>
              </div>
              ); // end IIFE return
            })() /* end IIFE */}
          </div>
        </div>
      </div>
    </div>
  );
}
