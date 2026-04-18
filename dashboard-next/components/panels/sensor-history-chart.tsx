"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { LineChart, Line, ResponsiveContainer, YAxis, XAxis, Tooltip, CartesianGrid } from "recharts";
import { TrendingUp } from "lucide-react";

export interface SensorTick {
  t: number;
  MQ2: number;
  MQ3: number;
  MQ5: number;
  MQ6: number;
  MQ7: number;
  MQ8: number;
  MQ135: number;
}

const lines: { key: keyof Omit<SensorTick, "t">; color: string; label: string }[] = [
  { key: "MQ2", color: "#60a5fa", label: "Flammable Gas" },
  { key: "MQ3", color: "#a78bfa", label: "Alcohol" },
  { key: "MQ5", color: "#f472b6", label: "LPG / Natural Gas" },
  { key: "MQ6", color: "#fb7185", label: "Butane / Propane" },
  { key: "MQ7", color: "#fbbf24", label: "Carbon Monoxide" },
  { key: "MQ8", color: "#34d399", label: "Hydrogen" },
  { key: "MQ135", color: "#22d3ee", label: "Air Quality" },
];

interface Props {
  data: SensorTick[];
}

export function SensorHistoryChart({ data }: Props) {
  return (
    <Card className="h-full">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium flex items-center gap-2">
          <TrendingUp className="size-3.5" />
          Sensor history · last {data.length} ticks
        </CardTitle>
        <div className="flex flex-wrap gap-2 text-[10px] text-muted-foreground">
          {lines.map((l) => (
            <span
              key={l.key}
              title={`${l.key} · ${l.label}`}
              className="inline-flex items-center gap-1 cursor-help"
            >
              <span className="size-2 rounded-full" style={{ background: l.color }} />
              {l.key}
            </span>
          ))}
        </div>
      </CardHeader>
      <CardContent>
        <div className="h-[220px]">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.12)" />
              <XAxis
                dataKey="t"
                tickFormatter={(v: number) => new Date(v).toLocaleTimeString().slice(3, 8)}
                stroke="rgba(148,163,184,0.6)"
                fontSize={10}
                tick={{ fill: "rgba(148,163,184,0.7)" }}
              />
              <YAxis
                stroke="rgba(148,163,184,0.6)"
                fontSize={10}
                tick={{ fill: "rgba(148,163,184,0.7)" }}
              />
              <Tooltip
                contentStyle={{
                  background: "rgba(15,23,42,0.95)",
                  border: "1px solid rgba(148,163,184,0.2)",
                  borderRadius: 8,
                  fontSize: 11,
                }}
                labelFormatter={(v) => new Date(v as number).toLocaleTimeString()}
              />
              {lines.map((l) => (
                <Line
                  key={l.key}
                  type="monotone"
                  dataKey={l.key}
                  stroke={l.color}
                  strokeWidth={1.5}
                  dot={false}
                  isAnimationActive={false}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
      </CardContent>
    </Card>
  );
}
