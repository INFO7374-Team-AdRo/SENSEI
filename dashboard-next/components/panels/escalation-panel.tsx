"use client";

import { useEffect, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Button } from "@/components/ui/button";
import { BellOff, Siren } from "lucide-react";
import { cn } from "@/lib/utils";
import type { EscalationTier } from "@/lib/api";

const DECAY_MS = 8000;

export interface EscalationEntry {
  ts: number;
  tier: EscalationTier;
  action: string;
}

interface Props {
  tier?: EscalationTier;
  action?: string;
  history: EscalationEntry[];
}

const tierLabel: Record<EscalationTier, string> = {
  NONE: "NORMAL",
  T1_LOG: "NOTIFY",
  T2_ALERT: "ALERT",
  T3_SHUTDOWN: "SHUTDOWN",
};

const tierRank: Record<EscalationTier, number> = {
  NONE: 0,
  T1_LOG: 1,
  T2_ALERT: 2,
  T3_SHUTDOWN: 3,
};

const tierStyle: Record<EscalationTier, string> = {
  NONE: "bg-muted/30 text-muted-foreground ring-border",
  T1_LOG: "bg-sky-500/15 text-sky-300 ring-sky-500/30",
  T2_ALERT: "bg-amber-500/20 text-amber-200 ring-amber-500/40",
  T3_SHUTDOWN: "bg-rose-700/30 text-rose-200 ring-rose-700/50 animate-pulse",
};

const tierBorder: Record<EscalationTier, string> = {
  NONE: "border-border/40",
  T1_LOG: "border-l-sky-500",
  T2_ALERT: "border-l-amber-500",
  T3_SHUTDOWN: "border-l-rose-600",
};

export function EscalationPanel({ tier, action, history }: Props) {
  const t = tier ?? "NONE";
  const currentRank = tierRank[t];

  // Hold the highest recent tier so brief drops to NONE (from classification jitter)
  // don't clear the ack/alarm UI. Only decay after DECAY_MS of continuous low state.
  const [heldTier, setHeldTier] = useState<EscalationTier>(t);
  const [heldAction, setHeldAction] = useState<string | undefined>(action);
  const [ackedHeld, setAckedHeld] = useState(false);
  const decayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const heldRank = tierRank[heldTier];
    if (currentRank > heldRank) {
      if (decayTimerRef.current) {
        clearTimeout(decayTimerRef.current);
        decayTimerRef.current = null;
      }
      setHeldTier(t);
      setHeldAction(action);
      setAckedHeld(false);
    } else if (currentRank === heldRank) {
      if (decayTimerRef.current) {
        clearTimeout(decayTimerRef.current);
        decayTimerRef.current = null;
      }
      if (action) setHeldAction(action);
    } else {
      if (!decayTimerRef.current) {
        decayTimerRef.current = setTimeout(() => {
          setHeldTier("NONE");
          setHeldAction(undefined);
          setAckedHeld(false);
          decayTimerRef.current = null;
        }, DECAY_MS);
      }
    }
  }, [t, action, currentRank, heldTier]);

  useEffect(() => () => {
    if (decayTimerRef.current) clearTimeout(decayTimerRef.current);
  }, []);

  const heldRank = tierRank[heldTier];
  const acknowledged = heldRank > 0 && ackedHeld;
  const displayTier: EscalationTier = acknowledged ? "NONE" : heldTier;
  const displayAction = acknowledged
    ? `Acknowledged · monitoring ${tierLabel[heldTier]} condition`
    : (heldAction ?? "Normal operations · all systems nominal");

  return (
    <Card className="h-full flex flex-col">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Escalation status
        </CardTitle>
        <Siren className="size-3.5 text-muted-foreground" />
      </CardHeader>
      <CardContent className="space-y-3 flex-1 flex flex-col min-h-0">
        <div
          className={cn(
            "rounded-xl ring-1 px-4 py-5 text-center font-bold tracking-wide text-xl",
            tierStyle[displayTier]
          )}
        >
          {tierLabel[displayTier]}
        </div>
        <p className="text-xs text-muted-foreground text-center min-h-[32px]">
          {displayAction}
        </p>
        {heldRank > 0 && !acknowledged && (
          <Button
            onClick={() => setAckedHeld(true)}
            size="sm"
            variant="outline"
            className="gap-1.5 self-center"
          >
            <BellOff className="size-3.5" />
            Acknowledge
          </Button>
        )}
        <div className="flex-1 min-h-0">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground mb-1.5">
            Recent escalations
          </div>
          <ScrollArea className="h-[160px] rounded-md border border-border/40 bg-background/40">
            <div className="p-1.5 space-y-1">
              {history.length === 0 ? (
                <div className="text-[11px] text-muted-foreground text-center py-6">
                  No escalations yet
                </div>
              ) : (
                history.map((e, i) => (
                  <div
                    key={i}
                    className={cn(
                      "rounded-md px-2 py-1.5 bg-background/60 border-l-2",
                      tierBorder[e.tier]
                    )}
                  >
                    <div className="flex items-center gap-2 text-[10px]">
                      <span className="font-mono tabular-nums text-muted-foreground">
                        {new Date(e.ts).toLocaleTimeString()}
                      </span>
                      <span className="font-semibold">{tierLabel[e.tier]}</span>
                    </div>
                    <div className="text-[11px] text-foreground/80 truncate">{e.action}</div>
                  </div>
                ))
              )}
            </div>
          </ScrollArea>
        </div>
      </CardContent>
    </Card>
  );
}
