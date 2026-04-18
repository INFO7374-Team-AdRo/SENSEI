"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import type { ClassificationLabel } from "@/lib/api";
import { Sparkles } from "lucide-react";

interface Props {
  label?: ClassificationLabel;
  confidence?: number;
}

const labelStyle: Record<ClassificationLabel, string> = {
  NO_GAS: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30",
  SMOKE: "bg-rose-500/20 text-rose-300 ring-rose-500/40",
  PERFUME: "bg-amber-500/20 text-amber-200 ring-amber-500/40",
  COMBINED: "bg-rose-700/30 text-rose-200 ring-rose-700/50 animate-pulse",
};

const probColor: Record<ClassificationLabel, string> = {
  NO_GAS: "bg-emerald-500",
  SMOKE: "bg-rose-500",
  PERFUME: "bg-amber-500",
  COMBINED: "bg-rose-700",
};

const ALL_LABELS: ClassificationLabel[] = ["NO_GAS", "SMOKE", "PERFUME", "COMBINED"];

export function ClassificationPanel({ label, confidence }: Props) {
  const active = label ?? "NO_GAS";
  const conf = confidence ?? 0;
  const confPct = Math.round(conf * 100);

  return (
    <Card className="h-full">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Hazard classification
        </CardTitle>
        <Sparkles className="size-3.5 text-muted-foreground" />
      </CardHeader>
      <CardContent className="space-y-4">
        <div
          className={cn(
            "rounded-xl ring-1 px-4 py-5 text-center font-bold tracking-wide text-2xl",
            labelStyle[active]
          )}
        >
          {active}
        </div>

        <div>
          <div className="flex justify-between text-[10px] uppercase tracking-wider text-muted-foreground mb-1.5">
            <span>Confidence</span>
            <span className="font-mono tabular-nums text-foreground/80">{confPct}%</span>
          </div>
          <div className="h-1.5 rounded-full bg-muted/60 overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-sky-500 to-violet-400 rounded-full transition-all duration-500"
              style={{ width: `${confPct}%` }}
            />
          </div>
        </div>

        <div className="space-y-2 pt-1">
          {ALL_LABELS.map((l) => {
            const p = l === active ? confPct : Math.round((100 - confPct) / 3);
            return (
              <div key={l} className="grid grid-cols-[80px_1fr_36px] items-center gap-3">
                <div
                  className={cn(
                    "text-[11px] font-medium",
                    l === active ? "text-foreground" : "text-muted-foreground"
                  )}
                >
                  {l.replace("_", " ")}
                </div>
                <div className="h-1.5 rounded-full bg-muted/60 overflow-hidden">
                  <div
                    className={cn("h-full rounded-full transition-all duration-500", probColor[l])}
                    style={{ width: `${p}%` }}
                  />
                </div>
                <div className="text-[10px] font-mono tabular-nums text-right text-muted-foreground">
                  {p}%
                </div>
              </div>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}
