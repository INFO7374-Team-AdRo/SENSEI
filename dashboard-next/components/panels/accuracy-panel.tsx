"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { Target } from "lucide-react";
import type { ClassificationLabel, IncidentReport } from "@/lib/api";

interface Props {
  events: IncidentReport[];
}

const ALL_LABELS: ClassificationLabel[] = ["NO_GAS", "SMOKE", "PERFUME", "COMBINED"];

export function AccuracyPanel({ events }: Props) {
  const evaluated = events.filter(
    (e) => e.groundTruthLabel && e.classificationLabel
  );
  const total = evaluated.length;
  const correct = evaluated.filter(
    (e) => e.groundTruthLabel === e.classificationLabel
  ).length;
  const accuracy = total > 0 ? (correct / total) * 100 : 0;

  const perLabel = ALL_LABELS.map((label) => {
    const truthCount = evaluated.filter((e) => e.groundTruthLabel === label).length;
    const hits = evaluated.filter(
      (e) => e.groundTruthLabel === label && e.classificationLabel === label
    ).length;
    const recall = truthCount > 0 ? (hits / truthCount) * 100 : null;
    return { label, truthCount, hits, recall };
  });

  const recent = evaluated.slice(0, 24);
  const MIN_N = 20;
  const warming = total < MIN_N;

  return (
    <Card className="h-full">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium flex items-center gap-2">
          <Target className="size-3.5" />
          Validation accuracy
        </CardTitle>
        <span className="text-[10px] text-muted-foreground tabular-nums">
          n={total}
        </span>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="text-center">
          <div
            className={cn(
              "text-4xl font-bold tabular-nums tracking-tight",
              warming
                ? "text-muted-foreground"
                : accuracy >= 85
                ? "text-emerald-300"
                : accuracy >= 70
                ? "text-amber-300"
                : "text-rose-300"
            )}
          >
            {warming ? "—" : `${accuracy.toFixed(1)}%`}
          </div>
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground mt-1">
            {warming
              ? `Warming up · ${total} / ${MIN_N} samples needed`
              : `${correct} / ${total} correct vs. CSV ground truth`}
          </div>
        </div>

        <div>
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground mb-1.5">
            Last {recent.length} predictions
          </div>
          <div className="flex flex-wrap gap-1">
            {recent.length === 0
              ? Array.from({ length: 24 }).map((_, i) => (
                  <span
                    key={i}
                    className="size-2.5 rounded-sm bg-muted/40"
                  />
                ))
              : recent.map((e, i) => {
                  const hit = e.groundTruthLabel === e.classificationLabel;
                  return (
                    <span
                      key={(e.id ?? "") + i}
                      title={`${e.classificationLabel} vs ${e.groundTruthLabel}`}
                      className={cn(
                        "size-2.5 rounded-sm ring-1",
                        hit
                          ? "bg-emerald-500/70 ring-emerald-500/40"
                          : "bg-rose-500/70 ring-rose-500/40"
                      )}
                    />
                  );
                })}
          </div>
        </div>

        <div className="space-y-1.5 pt-1">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
            Per-class recall
          </div>
          {perLabel.map(({ label, truthCount, hits, recall }) => (
            <div
              key={label}
              className="grid grid-cols-[80px_1fr_56px] items-center gap-3"
            >
              <div className="text-[11px] font-medium text-foreground/80">
                {label.replace("_", " ")}
              </div>
              <div className="h-1.5 rounded-full bg-muted/60 overflow-hidden">
                <div
                  className={cn(
                    "h-full rounded-full transition-all duration-500",
                    recall === null
                      ? "bg-muted"
                      : recall >= 85
                      ? "bg-emerald-500"
                      : recall >= 60
                      ? "bg-amber-500"
                      : "bg-rose-500"
                  )}
                  style={{ width: `${recall ?? 0}%` }}
                />
              </div>
              <div className="text-[10px] font-mono tabular-nums text-right text-muted-foreground">
                {recall === null ? "—" : `${hits}/${truthCount}`}
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
