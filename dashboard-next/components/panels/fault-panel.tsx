"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { CircuitBoard, Power, RotateCcw, CheckCircle2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { fetchFaultStatus, killShard, type ShardStatus } from "@/lib/api";

const SENSORS = ["MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"] as const;

const statusBadge: Record<ShardStatus, { label: string; cls: string; Icon: React.ComponentType<{ className?: string }> }> = {
  alive: { label: "alive", cls: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30", Icon: CheckCircle2 },
  killed: { label: "killed", cls: "bg-rose-500/20 text-rose-300 ring-rose-500/40 animate-pulse", Icon: Power },
  recovering: { label: "recovering", cls: "bg-amber-500/20 text-amber-200 ring-amber-500/40", Icon: RotateCcw },
};

export function FaultPanel() {
  const [shards, setShards] = useState<Record<string, ShardStatus>>({});

  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      const j = await fetchFaultStatus();
      if (cancelled) return;
      const next: Record<string, ShardStatus> = {};
      for (const s of SENSORS) next[s] = (j.shards?.[s]?.status ?? "alive") as ShardStatus;
      setShards(next);
    };
    tick();
    const id = setInterval(tick, 1500);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  return (
    <Card className="h-full">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Fault tolerance
        </CardTitle>
        <Badge variant="outline" className="gap-1.5 text-[10px]">
          <CircuitBoard className="size-3" /> Akka sharding
        </Badge>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-[11px] text-muted-foreground leading-relaxed">
          Click a sensor to stop its shard. Cluster Sharding rehydrates the entity on the next message
          and the EventSourced journal replays — last value returns automatically.
        </p>
        <div className="grid grid-cols-2 gap-2">
          {SENSORS.map((s) => {
            const st = (shards[s] ?? "alive") as ShardStatus;
            const meta = statusBadge[st];
            const Icon = meta.Icon;
            return (
              <Button
                key={s}
                variant="ghost"
                onClick={() => killShard(s)}
                className="h-auto justify-between rounded-lg border border-border/40 bg-background/40 px-3 py-2 hover:bg-background/70 hover:border-rose-500/40"
              >
                <span className="font-mono text-xs text-foreground">{s}</span>
                <span
                  className={cn(
                    "inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 ring-1 text-[10px] font-medium",
                    meta.cls
                  )}
                >
                  <Icon className="size-2.5" />
                  {meta.label}
                </span>
              </Button>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}
