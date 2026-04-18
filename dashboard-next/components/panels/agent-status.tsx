"use client";

import {
  Bot,
  Brain,
  CircuitBoard,
  Database,
  Flame,
  Network,
  Radar,
  ShieldAlert,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { AgentStatus } from "@/lib/api";

const AGENTS: { key: string; name: string; sub: string; Icon: React.ComponentType<{ className?: string }> }[] = [
  { key: "SensorAgent", name: "Sensor Agents", sub: "Sharded MQ2 → MQ135", Icon: CircuitBoard },
  { key: "ThermalAgent", name: "Thermal Agent", sub: "Camera frames", Icon: Flame },
  { key: "FusionAgent", name: "Fusion Agent", sub: "Cross-modal merge", Icon: Network },
  { key: "ClassificationAgent", name: "Classification", sub: "ONNX / XGBoost", Icon: Bot },
  { key: "RetrievalAgent", name: "Retrieval (RAG)", sub: "Qdrant vectors", Icon: Radar },
  { key: "ReasoningAgent", name: "LLM Reasoning", sub: "Mistral API", Icon: Brain },
  { key: "EscalationAgent", name: "Escalation", sub: "Tier policy", Icon: ShieldAlert },
  { key: "OrchestratorAgent", name: "Orchestrator", sub: "Receptionist + Ask", Icon: Database },
];

const dotClass: Record<AgentStatus, string> = {
  active: "bg-emerald-400 shadow-[0_0_10px_2px_rgba(74,222,128,0.6)]",
  warning: "bg-amber-400 shadow-[0_0_10px_2px_rgba(251,191,36,0.6)]",
  error: "bg-rose-500 shadow-[0_0_10px_2px_rgba(244,63,94,0.7)] animate-pulse",
  idle: "bg-muted-foreground/30",
};

interface Props {
  states?: Record<string, AgentStatus>;
}

export function AgentStatusPanel({ states }: Props) {
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Agent topology
        </CardTitle>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-2">
        {AGENTS.map(({ key, name, sub, Icon }) => {
          const status = (states?.[key] ?? "idle") as AgentStatus;
          return (
            <div
              key={key}
              className="flex items-center gap-3 rounded-lg border border-border/40 bg-background/40 p-3"
            >
              <div className="relative shrink-0">
                <div className="size-8 rounded-md bg-primary/10 ring-1 ring-primary/20 flex items-center justify-center">
                  <Icon className="size-4 text-primary/80" />
                </div>
                <span
                  className={cn(
                    "absolute -top-0.5 -right-0.5 size-2.5 rounded-full ring-2 ring-background",
                    dotClass[status]
                  )}
                />
              </div>
              <div className="min-w-0">
                <div className="text-xs font-medium truncate">{name}</div>
                <div className="text-[10px] text-muted-foreground truncate">{sub}</div>
              </div>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}
