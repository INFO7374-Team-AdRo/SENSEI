"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { ScrollText } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ClassificationLabel, EscalationTier, IncidentReport } from "@/lib/api";

interface Props {
  events: IncidentReport[];
}

const labelClass: Record<ClassificationLabel, string> = {
  NO_GAS: "text-emerald-400",
  SMOKE: "text-rose-400",
  PERFUME: "text-amber-300",
  COMBINED: "text-rose-500",
};

const tierClass: Record<EscalationTier, string> = {
  NONE: "text-muted-foreground",
  T1_LOG: "text-sky-300",
  T2_ALERT: "text-amber-300",
  T3_SHUTDOWN: "text-rose-400",
};

const tierLabel: Record<EscalationTier, string> = {
  NONE: "—",
  T1_LOG: "NOTIFY",
  T2_ALERT: "ALERT",
  T3_SHUTDOWN: "SHUTDOWN",
};

export function EventLog({ events }: Props) {
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium flex items-center gap-2">
          <ScrollText className="size-3.5" />
          Incident stream
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ScrollArea className="h-[260px] rounded-md border border-border/40 bg-background/30">
          <table className="w-full text-[11px] font-mono">
            <thead className="sticky top-0 bg-background/95 backdrop-blur text-[10px] uppercase tracking-wider text-muted-foreground">
              <tr>
                <th className="text-left px-3 py-2 font-medium">Time</th>
                <th className="text-left px-3 py-2 font-medium">ID</th>
                <th className="text-left px-3 py-2 font-medium">Class</th>
                <th className="text-right px-3 py-2 font-medium">Conf</th>
                <th className="text-left px-3 py-2 font-medium">Tier</th>
              </tr>
            </thead>
            <tbody>
              {events.length === 0 ? (
                <tr>
                  <td colSpan={5} className="text-center py-8 text-muted-foreground">
                    Waiting for incidents…
                  </td>
                </tr>
              ) : (
                events.map((e, i) => {
                  const label = (e.classificationLabel ?? "NO_GAS") as ClassificationLabel;
                  const tier = (e.escalationTier ?? "NONE") as EscalationTier;
                  const conf = Math.round((e.classificationConfidence ?? 0) * 100);
                  return (
                    <tr key={(e.id ?? "") + i} className="border-t border-border/30">
                      <td className="px-3 py-1.5 text-muted-foreground">
                        {e.timestampMs ? new Date(e.timestampMs).toLocaleTimeString() : "--:--:--"}
                      </td>
                      <td className="px-3 py-1.5 text-sky-300 truncate max-w-[140px]">
                        {e.id ?? "--"}
                      </td>
                      <td className={cn("px-3 py-1.5 font-semibold", labelClass[label])}>
                        {label}
                      </td>
                      <td className="px-3 py-1.5 text-right tabular-nums text-foreground/80">
                        {conf}%
                      </td>
                      <td className={cn("px-3 py-1.5", tierClass[tier])}>{tierLabel[tier]}</td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
