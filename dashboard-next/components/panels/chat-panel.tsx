"use client";

import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { MessageSquare, Send, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { sendChat } from "@/lib/api";

interface Msg {
  role: "user" | "bot";
  text: string;
}

export function ChatPanel() {
  const [messages, setMessages] = useState<Msg[]>([
    {
      role: "bot",
      text: "Ask about current hazards, recent incidents, or recommended actions.",
    },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit() {
    const q = input.trim();
    if (!q || busy) return;
    setMessages((m) => [...m, { role: "user", text: q }]);
    setInput("");
    setBusy(true);
    const { answer } = await sendChat(q, "dashboard");
    setMessages((m) => [...m, { role: "bot", text: answer || "(no answer)" }]);
    setBusy(false);
  }

  return (
    <Card className="h-full flex flex-col">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-xs uppercase tracking-wider text-muted-foreground font-medium">
          Operator chat · NL → LLM
        </CardTitle>
        <MessageSquare className="size-3.5 text-muted-foreground" />
      </CardHeader>
      <CardContent className="flex flex-col gap-2 flex-1 min-h-0">
        <ScrollArea className="flex-1 min-h-0 rounded-md border border-border/40 bg-background/40 px-2 py-2">
          <div className="space-y-2">
            {messages.map((m, i) => (
              <div
                key={i}
                className={cn(
                  "rounded-lg px-3 py-1.5 text-xs leading-relaxed max-w-[92%]",
                  m.role === "user"
                    ? "bg-primary/10 text-foreground self-end ring-1 ring-primary/20 ml-auto"
                    : "bg-background/60 text-foreground/85 ring-1 ring-border/40"
                )}
              >
                {m.text}
              </div>
            ))}
            {busy && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground px-1">
                <Loader2 className="size-3 animate-spin" />
                Thinking…
              </div>
            )}
          </div>
        </ScrollArea>
        <div className="flex gap-2">
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
            }}
            placeholder="What's the current hazard level?"
            className="text-xs"
          />
          <Button onClick={submit} disabled={busy || !input.trim()} size="sm" className="gap-1.5">
            <Send className="size-3" />
            Send
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
