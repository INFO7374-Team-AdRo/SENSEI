// Client for the SafetyHttpServer (Akka backend on :8080).
// Uses Next.js rewrites so calls stay same-origin.

export type ClassificationLabel = "NO_GAS" | "SMOKE" | "PERFUME" | "COMBINED";
export type EscalationTier = "NONE" | "T1_LOG" | "T2_ALERT" | "T3_SHUTDOWN";
export type AgentStatus = "active" | "warning" | "error" | "idle";
export type ShardStatus = "alive" | "killed" | "recovering";

export interface SimilarIncident {
  description?: string;
  resolution?: string;
  similarityScore?: number;
}

export interface IncidentReport {
  id?: string;
  timestampMs?: number;
  sensorValues?: Record<string, number>;
  maxTemp?: number;
  avgTemp?: number;
  classificationLabel?: ClassificationLabel;
  classificationConfidence?: number;
  groundTruthLabel?: ClassificationLabel | null;
  escalationTier?: EscalationTier;
  escalationAction?: string;
  llmAnalysis?: string;
  llmRecommendation?: string;
  similarIncidents?: SimilarIncident[];
  agentStates?: Record<string, AgentStatus>;
}

export interface ShardInfo {
  status: ShardStatus;
  lastPpm?: number;
  killedAt?: string;
}
export interface FaultStatus {
  shards: Record<string, ShardInfo>;
}

export interface SystemStatus {
  events?: number;
  mongoConnected?: boolean;
  reasoningOnline?: boolean;
  uptimeSeconds?: number;
  [k: string]: unknown;
}

export async function fetchStatus(): Promise<SystemStatus | null> {
  try {
    const res = await fetch("/api-status", { cache: "no-store" });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

export async function fetchIncidents(): Promise<IncidentReport[]> {
  try {
    const res = await fetch("/api-incidents", { cache: "no-store" });
    if (!res.ok) return [];
    return await res.json();
  } catch {
    return [];
  }
}

export async function fetchFaultStatus(): Promise<FaultStatus> {
  try {
    const res = await fetch("/api-fault/status", { cache: "no-store" });
    if (!res.ok) return { shards: {} };
    return await res.json();
  } catch {
    return { shards: {} };
  }
}

export async function killShard(sensorType: string): Promise<void> {
  try {
    await fetch(`/api-fault/kill/${sensorType}`, { method: "POST" });
  } catch {
    /* no-op */
  }
}

export async function sendChat(
  query: string,
  conversationId = "dashboard"
): Promise<{ answer: string }> {
  try {
    const res = await fetch("/api-chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query, conversationId }),
    });
    if (!res.ok) return { answer: `Backend returned ${res.status}` };
    return await res.json();
  } catch (e) {
    return { answer: `Network error: ${(e as Error).message}` };
  }
}
