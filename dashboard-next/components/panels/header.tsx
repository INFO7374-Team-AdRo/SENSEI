"use client";

import { Activity, ShieldCheck, Wifi, WifiOff } from "lucide-react";
import { Badge } from "@/components/ui/badge";

interface HeaderProps {
  connected: boolean;
  eventCount: number;
  lastTimestamp?: number;
}

export function Header({ connected, eventCount, lastTimestamp }: HeaderProps) {
  const time = lastTimestamp ? new Date(lastTimestamp).toLocaleTimeString() : "--:--:--";
  return (
    <header className="border-b border-border/40 bg-card/30 backdrop-blur-md sticky top-0 z-30">
      <div className="mx-auto max-w-[1600px] px-6 py-4 flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="size-10 rounded-xl bg-primary/10 ring-1 ring-primary/30 flex items-center justify-center">
            <ShieldCheck className="size-5 text-primary" />
          </div>
          <h1 className="text-base font-semibold tracking-tight text-foreground">
            Industrial Safety Monitoring
          </h1>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant="outline" className="gap-1.5">
            <Activity className="size-3" />
            {eventCount} events
          </Badge>
          <Badge variant="outline" className="font-mono text-[10px]">
            {time}
          </Badge>
          {connected ? (
            <Badge className="gap-1.5 bg-emerald-500/15 text-emerald-400 border-emerald-500/30 border">
              <Wifi className="size-3" />
              Live
            </Badge>
          ) : (
            <Badge variant="destructive" className="gap-1.5 border border-destructive/30">
              <WifiOff className="size-3" />
              Disconnected
            </Badge>
          )}
        </div>
      </div>
    </header>
  );
}
