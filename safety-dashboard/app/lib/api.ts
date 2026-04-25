const API_BASE = "http://localhost:8080";

export interface EscalationEvent {
  hazardType: string;
  severity: string;
  confidence: number;
  recommendation: string;
  timestamp: string;
}

export interface IncidentEvent {
  incidentId:     string;
  hazardType:     string;
  severity:       string;
  recommendation: string;
  confidence:     number;
  tier:           string;           // "T3_SHUTDOWN" | "T2_ALERT" | "T1_NORMAL" | "INSPECSAFE"
  timestamp:      string;
  // InspecSafe-only fields (present when tier === "INSPECSAFE")
  source?:        string;           // "inspecsafe"
  waypointId?:    string;
  robotId?:       string;
  location?:      string;
  environment?:   string;
  anomalyType?:   string;
  anomalyCode?:   string;
  safetyGrade?:   string;
  description?:   string;
  imageUrl?:      string | null;
  audioUrl?:      string | null;
  infraredUrl?:   string | null;   // InspecSafe IR video (.mp4)
  coReading?:     number | null;   // CO  PPM
  ch4Level?:      number | null;   // CH4 %VOL (methane)
  o2Level?:       number | null;   // O2  %VOL (oxygen)
  h2sReading?:    number | null;   // H2S PPM  (hydrogen sulphide)
  gasReading?:    number | null;   // legacy alias kept for backward compat
  temperature?:   number | null;
  humidity?:      number | null;
  soundLevel?:    number | null;
}

// ── Unified event stream ──────────────────────────────────────────────────────
// Single endpoint that returns everything the dashboard needs: latest sensor
// reading, escalations, inspection events, thermal state, status, and history.
// The frontend uses ONE 2-second interval instead of six separate polls.
export interface UnifiedEvents {
  latestSensor?:  Record<string, number | string | null>;
  escalations:    EscalationEvent[];
  inspections:    IncidentEvent[];          // InspectionEvent shape
  thermal: {
    temperature: number; anomaly: boolean;
    currentFrameUrl?: string | null; currentLabel?: string | null; hasImage?: boolean;
  };
  status: {
    sensorShards: number; recentSensorEvents: number; recentEscalationEvents: number;
    totalSensorEvents: number; totalEscalationEvents: number; totalIncidents: number;
    latestClassification: string | null; clusterNode: string;
  };
  sensorHistory: SensorHistoryPoint[];
}

export async function fetchEvents(): Promise<UnifiedEvents | null> {
  try {
    const res = await fetch(`${API_BASE}/api-events`, { cache: "no-store" });
    if (!res.ok) return null;
    return res.json();
  } catch { return null; }
}

export async function fetchSensors() {
  const res = await fetch(`${API_BASE}/api-sensors`, { cache: "no-store" });
  if (!res.ok) return [];
  const data = await res.json();
  return data.map((d: string) => (typeof d === "string" ? JSON.parse(d) : d));
}

export async function fetchEscalations(): Promise<EscalationEvent[]> {
  const res = await fetch(`${API_BASE}/api-escalations`, { cache: "no-store" });
  if (!res.ok) return [];
  const data = await res.json();
  return data.map((d: string) => (typeof d === "string" ? JSON.parse(d) : d));
}

export async function fetchIncidents(): Promise<IncidentEvent[]> {
  const res = await fetch(`${API_BASE}/api-incidents`, { cache: "no-store" });
  if (!res.ok) return [];
  return res.json();
}

export async function fetchThermal(): Promise<{
  temperature: number;
  anomaly: boolean;
  unit: string;
  currentFrameUrl?: string | null;
  currentLabel?: string | null;
  hasImage?: boolean;
} | null> {
  const res = await fetch(`${API_BASE}/api-thermal`, { cache: "no-store" });
  if (!res.ok) return null;
  return res.json();
}

export async function fetchStatus() {
  const res = await fetch(`${API_BASE}/api-status`, { cache: "no-store" });
  if (!res.ok) return null;
  return res.json();
}

export async function sendChat(query: string, conversationId: string = "default") {
  const res = await fetch(`${API_BASE}/api-chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query, conversationId }),
  });
  if (!res.ok) return { answer: "Failed to get response", queryType: "error", sourcesUsed: [] };
  return res.json() as Promise<{
    answer: string;
    queryType: string;
    sourcesUsed: string[];
    imageUrl?: string;
    audioUrl?: string;
    infraredUrl?: string;
  }>;
}

export async function fetchIncidentReport(incidentId: string) {
  const res = await fetch(`${API_BASE}/api-report/${incidentId}`, { cache: "no-store" });
  if (!res.ok) return null;
  return res.json();
}

export interface ShardInfo {
  status: "alive" | "killed" | "recovering";
  lastPpm: number;
  msgCount?: number;
  killCount?: number;
  killedAt?: string;
}

export interface FaultStatus {
  shards: Record<string, ShardInfo>;
}

export async function fetchFaultStatus(): Promise<FaultStatus> {
  const res = await fetch(`${API_BASE}/api-fault/status`, { cache: "no-store" });
  if (!res.ok) return { shards: {} };
  return res.json();
}

export async function killShard(sensorType: string): Promise<void> {
  await fetch(`${API_BASE}/api-fault/kill/${sensorType}`, { method: "POST" });
}

export interface SensorHistoryPoint {
  timestamp: string;
  MQ2?: number; MQ3?: number; MQ5?: number; MQ6?: number;
  MQ7?: number; MQ8?: number; MQ135?: number;
}

export async function fetchSensorHistory(): Promise<SensorHistoryPoint[]> {
  const res = await fetch(`${API_BASE}/api-sensor-history`, { cache: "no-store" });
  if (!res.ok) return [];
  return res.json();
}
