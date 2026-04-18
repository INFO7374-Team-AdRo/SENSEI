"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Brain, BookOpen, Lightbulb } from "lucide-react";
import type { SimilarIncident } from "@/lib/api";

interface Props {
  analysis?: string;
  recommendation?: string;
  similar?: SimilarIncident[];
}

export function LLMPanel({ analysis, recommendation, similar }: Props) {
  return (
    <Card className="h-full">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          LLM analysis · Mistral
        </CardTitle>
        <Brain className="size-3.5 text-muted-foreground" />
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="space-y-2">
            <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-muted-foreground">
              <Brain className="size-3" /> Analysis
            </div>
            <p className="text-xs leading-relaxed text-foreground/85 min-h-[64px]">
              {analysis ?? "Waiting for hazard events…"}
            </p>
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-muted-foreground">
              <Lightbulb className="size-3" /> Recommendation
            </div>
            <p className="text-xs leading-relaxed text-foreground/85 min-h-[64px]">
              {recommendation ?? "—"}
            </p>
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-muted-foreground">
              <BookOpen className="size-3" /> Similar incidents · RAG
            </div>
            <div className="space-y-1.5 max-h-[160px] overflow-auto pr-1">
              {!similar || similar.length === 0 ? (
                <p className="text-[11px] text-muted-foreground">No incidents retrieved yet.</p>
              ) : (
                similar.map((inc, i) => (
                  <div
                    key={i}
                    className="rounded-md bg-background/50 border border-border/40 border-l-2 border-l-violet-500/70 p-2"
                  >
                    <div className="text-[10px] text-violet-300 font-semibold">
                      Similarity · {Math.round((inc.similarityScore ?? 0) * 100)}%
                    </div>
                    <div className="text-[11px] text-foreground/85 line-clamp-2">
                      {inc.description}
                    </div>
                    {inc.resolution && (
                      <div className="text-[10px] text-muted-foreground line-clamp-2 mt-1">
                        {inc.resolution}
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
