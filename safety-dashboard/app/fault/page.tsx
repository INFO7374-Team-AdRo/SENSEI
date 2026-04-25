"use client";
import { useState, useEffect, useRef } from "react";
import { fetchFaultStatus, killShard, FaultStatus, ShardInfo } from "../lib/api";
import { Zap, RefreshCw, AlertTriangle, CheckCircle, Activity, Terminal, Skull } from "lucide-react";

const SENSOR_TYPES = ["MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"];

const SENSOR_LABELS: Record<string, string> = {
  MQ2: "Flammable",
  MQ3: "Alcohol",
  MQ5: "LPG",
  MQ6: "Butane",
  MQ7: "CO",
  MQ8: "Hydrogen",
  MQ135: "Air Quality",
};

const SENSOR_THRESHOLDS: Record<string, number> = {
  MQ2: 500, MQ3: 400, MQ5: 500, MQ6: 500, MQ7: 100, MQ8: 500, MQ135: 400,
};

type LogEntry = { time: string; message: string; type: "kill" | "recover" | "info" };

function ShardCard({
  sensor,
  info,
  onKill,
}: {
  sensor: string;
  info: ShardInfo | undefined;
  onKill: (s: string) => void;
}) {
  const status = info?.status ?? "alive";
  const ppm = info?.lastPpm ?? 0;
  const threshold = SENSOR_THRESHOLDS[sensor];
  const breached = ppm > threshold;
  const killCount = info?.killCount ?? 0;
  const msgCount = info?.msgCount ?? 0;

  const isKilled = status === "killed";
  const isRecovering = status === "recovering";
  const isAlive = status === "alive";

  const cardBg = isKilled
    ? "bg-red-950/60 border-red-600/60"
    : isRecovering
    ? "bg-yellow-950/50 border-yellow-500/50"
    : "bg-gray-900 border-gray-800";

  const ppmPct = Math.min(100, (ppm / (threshold * 2)) * 100);

  return (
    <div className={`rounded-xl border p-4 space-y-3 transition-all duration-300 ${cardBg} relative overflow-hidden`}>
      {/* Killed overlay pulse */}
      {isKilled && (
        <div className="absolute inset-0 bg-red-600/10 animate-pulse pointer-events-none" />
      )}
      {isRecovering && (
        <div className="absolute inset-0 bg-yellow-500/5 animate-pulse pointer-events-none" />
      )}

      {/* Header */}
      <div className="flex items-center justify-between relative z-10">
        <div>
          <span className="font-bold text-white text-lg">{sensor}</span>
          <div className="text-[10px] text-gray-500">{SENSOR_LABELS[sensor]}</div>
        </div>
        <div className="flex flex-col items-end gap-1">
          {isAlive && (
            <span className="flex items-center gap-1 text-xs text-green-400">
              <CheckCircle size={12} />
              Alive
            </span>
          )}
          {isKilled && (
            <span className="flex items-center gap-1 text-xs text-red-400 font-bold animate-pulse">
              <Skull size={12} />
              KILLED
            </span>
          )}
          {isRecovering && (
            <span className="flex items-center gap-1 text-xs text-yellow-400 font-bold">
              <RefreshCw size={12} className="animate-spin" />
              RECOVERING
            </span>
          )}
          {killCount > 0 && (
            <span className="text-[10px] text-gray-500">
              killed×{killCount}
            </span>
          )}
        </div>
      </div>

      {/* PPM bar */}
      <div className="relative z-10 space-y-1">
        <div className="flex justify-between text-[10px] text-gray-500">
          <span>PPM</span>
          <span className={breached ? "text-red-400 font-bold" : "text-gray-400"}>
            {ppm} / {threshold}
          </span>
        </div>
        <div className="h-2 bg-gray-800 rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ${
              isKilled
                ? "bg-red-600"
                : breached
                ? "bg-orange-500"
                : "bg-blue-500"
            }`}
            style={{ width: `${ppmPct}%` }}
          />
        </div>
      </div>

      {/* Stats */}
      <div className="text-[10px] text-gray-500 flex gap-3 relative z-10">
        <span>
          <Activity size={9} className="inline mr-0.5" />
          {msgCount.toLocaleString()} msgs
        </span>
        {info?.killedAt && isKilled && (
          <span className="text-red-400">
            killed {new Date(info.killedAt).toLocaleTimeString()}
          </span>
        )}
      </div>

      {/* Kill button */}
      <div className="relative z-10">
        {isAlive && (
          <button
            onClick={() => onKill(sensor)}
            className="w-full text-xs py-2 rounded-lg bg-red-600/20 border border-red-600/40
                       text-red-400 hover:bg-red-600/30 hover:border-red-500
                       transition-all duration-200 font-medium flex items-center justify-center gap-1.5"
          >
            <Skull size={12} />
            Kill Shard
          </button>
        )}
        {isKilled && (
          <div className="w-full text-xs py-2 rounded-lg bg-red-600/10 border border-red-600/30
                          text-red-500 text-center animate-pulse">
            Shard stopped — messages buffering...
          </div>
        )}
        {isRecovering && (
          <div className="w-full text-xs py-2 rounded-lg bg-yellow-500/10 border border-yellow-500/30
                          text-yellow-400 text-center flex items-center justify-center gap-1.5">
            <RefreshCw size={11} className="animate-spin" />
            Akka restarting entity...
          </div>
        )}
      </div>
    </div>
  );
}

export default function FaultPage() {
  const [status, setStatus] = useState<FaultStatus>({ shards: {} });
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [prevStatuses, setPrevStatuses] = useState<Record<string, string>>({});
  const logRef = useRef<HTMLDivElement>(null);

  const addLog = (message: string, type: LogEntry["type"]) => {
    setLogs(prev => [
      { time: new Date().toLocaleTimeString(), message, type },
      ...prev.slice(0, 49),
    ]);
  };

  const refresh = async () => {
    const s = await fetchFaultStatus();
    setStatus(s);
    setLastRefresh(new Date());

    // Detect status transitions for the event log
    setPrevStatuses(prev => {
      const next: Record<string, string> = {};
      for (const sensor of SENSOR_TYPES) {
        const newStatus = s.shards[sensor]?.status ?? "alive";
        const oldStatus = prev[sensor] ?? "alive";
        if (oldStatus !== newStatus) {
          if (newStatus === "killed") {
            addLog(`⚡ ${sensor} shard killed — Akka buffering incoming messages`, "kill");
          } else if (newStatus === "recovering") {
            addLog(`🔄 ${sensor} recovering — entity re-created on next message`, "recover");
          } else if (newStatus === "alive" && oldStatus === "recovering") {
            const killCount = s.shards[sensor]?.killCount ?? 1;
            addLog(`✅ ${sensor} fully restored (kill #${killCount}) — no data lost`, "info");
          }
        }
        next[sensor] = newStatus;
      }
      return next;
    });
  };

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 500);
    return () => clearInterval(id);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleKill = async (sensor: string) => {
    addLog(`🎯 Sending Stop command to SensorAgent[${sensor}]...`, "kill");
    await killShard(sensor);
    addLog(`☠️ Stop sent — ${sensor} entity will halt after current message`, "kill");
  };

  const aliveCount = SENSOR_TYPES.filter(
    s => (status.shards[s]?.status ?? "alive") === "alive"
  ).length;
  const killedCount = SENSOR_TYPES.filter(
    s => status.shards[s]?.status === "killed"
  ).length;
  const recoveringCount = SENSOR_TYPES.filter(
    s => status.shards[s]?.status === "recovering"
  ).length;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white flex items-center gap-2">
            <Zap size={22} className="text-yellow-400" />
            Fault Tolerance Demo
          </h1>
          <p className="text-gray-400 text-sm mt-1">
            Deliberately kill a SensorAgent shard — watch Akka Cluster Sharding auto-recover it
          </p>
        </div>
        <div className="flex items-center gap-4">
          {/* Summary counters */}
          <div className="flex gap-3 text-xs">
            <span className="px-2 py-1 rounded bg-green-500/15 text-green-400 border border-green-500/20">
              {aliveCount} alive
            </span>
            {killedCount > 0 && (
              <span className="px-2 py-1 rounded bg-red-500/15 text-red-400 border border-red-500/20 animate-pulse">
                {killedCount} killed
              </span>
            )}
            {recoveringCount > 0 && (
              <span className="px-2 py-1 rounded bg-yellow-500/15 text-yellow-400 border border-yellow-500/20">
                {recoveringCount} recovering
              </span>
            )}
          </div>
          <div className="flex items-center gap-1.5 text-xs text-gray-500">
            <RefreshCw size={11} className="animate-spin" />
            {lastRefresh.toLocaleTimeString()}
          </div>
        </div>
      </div>

      {/* Shard cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 md:grid-cols-7">
        {SENSOR_TYPES.map(sensor => (
          <ShardCard
            key={sensor}
            sensor={sensor}
            info={status.shards[sensor]}
            onKill={handleKill}
          />
        ))}
      </div>

      {/* Two-column lower section */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

        {/* How it works */}
        <div className="rounded-xl border border-gray-800 bg-gray-900 p-5 space-y-4">
          <h2 className="text-sm font-semibold text-white flex items-center gap-2">
            <Zap size={14} className="text-yellow-400" />
            How Akka Fault Tolerance Works Here
          </h2>
          <div className="space-y-3 text-xs text-gray-400">
            <div className="flex gap-3">
              <span className="text-red-400 font-bold w-4 shrink-0">1</span>
              <div>
                <span className="text-white font-medium">Kill</span> — "Kill Shard" sends{" "}
                <code className="text-yellow-300 bg-gray-800 px-1 rounded">SensorAgent.Stop</code>{" "}
                to the Akka Cluster Sharding entity, which calls{" "}
                <code className="text-yellow-300 bg-gray-800 px-1 rounded">Behaviors.stopped()</code>.
                The entity is removed from the shard region.
              </div>
            </div>
            <div className="flex gap-3">
              <span className="text-orange-400 font-bold w-4 shrink-0">2</span>
              <div>
                <span className="text-white font-medium">Buffer</span> — While the shard is down,{" "}
                DataReplayStream keeps firing{" "}
                <code className="text-yellow-300 bg-gray-800 px-1 rounded">ProcessReading</code>{" "}
                every 200ms. Akka Cluster Sharding <em>buffers</em> these messages — nothing is lost.
              </div>
            </div>
            <div className="flex gap-3">
              <span className="text-yellow-400 font-bold w-4 shrink-0">3</span>
              <div>
                <span className="text-white font-medium">Recreate</span> — On the next message
                delivery (~200ms), Akka automatically calls{" "}
                <code className="text-yellow-300 bg-gray-800 px-1 rounded">SensorAgent.create(entityId)</code>{" "}
                and re-creates the entity in the region.{" "}
                <code className="text-yellow-300 bg-gray-800 px-1 rounded">SetFusionRef</code>{" "}
                is re-sent on every row so the restarted shard immediately re-wires itself.
              </div>
            </div>
            <div className="flex gap-3">
              <span className="text-green-400 font-bold w-4 shrink-0">4</span>
              <div>
                <span className="text-white font-medium">Restore</span> — The shard drains its
                buffered messages, resumes processing, and the pipeline continues exactly where it
                left off. Zero data loss, zero operator intervention.
              </div>
            </div>
          </div>
        </div>

        {/* Event log */}
        <div className="rounded-xl border border-gray-800 bg-gray-900 p-4 space-y-3">
          <h2 className="text-sm font-semibold text-white flex items-center gap-2">
            <Terminal size={14} className="text-green-400" />
            Live Event Log
          </h2>
          <div
            ref={logRef}
            className="h-52 overflow-y-auto space-y-1 font-mono text-xs pr-1"
          >
            {logs.length === 0 ? (
              <p className="text-gray-600 text-center pt-8">
                Click <span className="text-red-400">Kill Shard</span> on any card to start the demo
              </p>
            ) : (
              logs.map((entry, i) => (
                <div
                  key={i}
                  className={`flex gap-2 ${
                    i === 0 ? "opacity-100" : "opacity-60"
                  }`}
                >
                  <span className="text-gray-600 shrink-0">{entry.time}</span>
                  <span
                    className={
                      entry.type === "kill"
                        ? "text-red-300"
                        : entry.type === "recover"
                        ? "text-yellow-300"
                        : "text-green-300"
                    }
                  >
                    {entry.message}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Supervision hierarchy callout */}
      <div className="rounded-xl border border-gray-700 bg-gray-900/50 p-4">
        <h2 className="text-xs font-semibold text-gray-400 mb-3">
          Supervision Hierarchy (let it crash)
        </h2>
        <div className="font-mono text-xs text-gray-500 space-y-0.5">
          <div className="text-blue-400">SafetyGuardian <span className="text-gray-600">(root — restart on failure)</span></div>
          <div className="pl-4 text-purple-400">├── ReasoningSupervisor <span className="text-gray-600">→ restartWithBackoff(1s→30s)</span></div>
          <div className="pl-8 text-gray-400">│   ├── RetrievalAgent <span className="text-gray-600">(Qdrant RAG)</span></div>
          <div className="pl-8 text-gray-400">│   └── LLMReasoningAgent <span className="text-gray-600">(Mistral)</span></div>
          <div className="pl-4 text-orange-400">├── OutputSupervisor <span className="text-gray-600">→ restart</span></div>
          <div className="pl-8 text-gray-400">│   ├── OrchestratorAgent <span className="text-gray-600">(MongoDB)</span></div>
          <div className="pl-8 text-gray-400">│   └── EscalationAgent</div>
          <div className="pl-4 text-green-400">└── SensingSupervisor <span className="text-gray-600">→ restart</span></div>
          <div className="pl-8 text-gray-400">    ├── ClassificationAgent <span className="text-gray-600">(PMML)</span></div>
          <div className="pl-8 text-gray-400">    ├── FusionAgent</div>
          <div className="pl-8 text-gray-400">    └── ThermalAgent <span className="text-gray-600">→ restartWithBackoff(1s→30s) (Mistral Vision)</span></div>
          <div className="pl-0 text-cyan-400 mt-1">ClusterSharding <span className="text-gray-600">→ SensorAgent[MQ2..MQ135] <span className="text-yellow-400">← you are killing these ↑</span></span></div>
        </div>
      </div>
    </div>
  );
}
