"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Thermometer } from "lucide-react";
import { cn } from "@/lib/utils";

const SENSORS: { key: string; label: string; max: number }[] = [
  { key: "MQ2", label: "Flammable Gas", max: 2000 },
  { key: "MQ3", label: "Alcohol", max: 1000 },
  { key: "MQ5", label: "LPG / Natural Gas", max: 1500 },
  { key: "MQ6", label: "Butane / Propane", max: 1500 },
  { key: "MQ7", label: "Carbon Monoxide", max: 100 },
  { key: "MQ8", label: "Hydrogen", max: 2000 },
  { key: "MQ135", label: "Air Quality", max: 800 },
];

interface Props {
  sensorValues?: Record<string, number>;
  maxTemp?: number;
  avgTemp?: number;
}

function severity(pct: number): "low" | "warn" | "danger" {
  if (pct >= 80) return "danger";
  if (pct >= 50) return "warn";
  return "low";
}

const barTrack: Record<string, string> = {
  low: "from-sky-500/60 to-sky-400/80",
  warn: "from-amber-500/70 to-amber-400/90",
  danger: "from-rose-600 to-rose-400",
};

export function SensorPanel({ sensorValues, maxTemp, avgTemp }: Props) {
  const tempAnomaly = (maxTemp ?? 0) > 50;
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Sensor readings · ppm
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2.5">
        {SENSORS.map(({ key, label, max }) => {
          const v = sensorValues?.[key] ?? 0;
          const pct = Math.min((v / max) * 100, 100);
          const sev = severity(pct);
          return (
            <div key={key} className="grid grid-cols-[78px_1fr_56px] items-center gap-3">
              <div className="leading-tight">
                <div className="text-xs font-semibold tracking-tight">{key}</div>
                <div className="text-[10px] text-muted-foreground truncate">{label}</div>
              </div>
              <div className="h-2 rounded-full bg-muted/60 overflow-hidden relative">
                <div
                  className={cn(
                    "h-full rounded-full bg-gradient-to-r transition-all duration-500",
                    barTrack[sev]
                  )}
                  style={{ width: `${pct}%` }}
                />
              </div>
              <div
                className={cn(
                  "text-xs font-mono tabular-nums text-right",
                  sev === "danger"
                    ? "text-rose-400"
                    : sev === "warn"
                      ? "text-amber-300"
                      : "text-foreground/80"
                )}
              >
                {v.toFixed(1)}
              </div>
            </div>
          );
        })}

        <div className="mt-4 pt-4 border-t border-border/40 grid grid-cols-3 gap-2 items-center">
          <div className="flex items-center gap-2">
            <div
              className={cn(
                "size-9 rounded-lg ring-1 flex items-center justify-center",
                tempAnomaly
                  ? "bg-rose-500/15 ring-rose-500/40 text-rose-400"
                  : "bg-emerald-500/10 ring-emerald-500/30 text-emerald-400"
              )}
            >
              <Thermometer className="size-4" />
            </div>
            <div>
              <div className="text-[10px] uppercase tracking-wider text-muted-foreground">Thermal</div>
              <div className="text-xs font-medium">{tempAnomaly ? "Anomaly" : "Normal"}</div>
            </div>
          </div>
          <div className="text-center">
            <div className="text-[10px] uppercase tracking-wider text-muted-foreground">Max</div>
            <div className="font-mono text-sm tabular-nums">
              {(maxTemp ?? 0).toFixed(1)}°C
            </div>
          </div>
          <div className="text-center">
            <div className="text-[10px] uppercase tracking-wider text-muted-foreground">Avg</div>
            <div className="font-mono text-sm tabular-nums">
              {(avgTemp ?? 0).toFixed(1)}°C
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
