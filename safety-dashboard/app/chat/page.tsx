// safety-dashboard/app/chat/page.tsx
"use client";
import { useState, useRef, useEffect, useCallback } from "react";
import { sendChat } from "../lib/api";
import {
  MessageSquare, Send, Bot, User, Loader2,
  AlertTriangle, Database, Search, Plus, Clock,
} from "lucide-react";

interface Message {
  role: "user" | "assistant";
  content: string;
  queryType?: string;
  sourcesUsed?: string[];
  timestamp: Date;
  error?: boolean;
  imageUrl?: string;
  audioUrl?: string;
  infraredUrl?: string;
}

interface ConvMeta {
  conversationId: string;
  title: string;
  lastActivity: string;
}

const API_BASE = "http://localhost:8080";

const SUGGESTED_QUERIES = [
  "What is the current system status?",
  "Summarize the most recent escalation",
  "Which sensors are currently breached?",
  "What was the confidence level of the last classification?",
  "Are there any critical incidents I should know about?",
];

const WELCOME: Message = {
  role: "assistant",
  content: "Hello! I'm your industrial safety assistant powered by Mistral. Ask me about live sensor readings, escalation events, hazard classifications, or incident history — I only reference real system data.",
  timestamp: new Date(),
  queryType: "greeting",
  sourcesUsed: [],
};

// Java Instant can emit nanosecond precision — strip to 3 decimal places so JS Date handles it
function parseIso(iso?: string | null): Date | null {
  if (!iso) return null;
  const fixed = iso.replace(/(\.\d{3})\d+Z$/, "$1Z").replace(/(\.\d{1,2})Z$/, (_, p1) => p1.padEnd(4, "0") + "Z");
  const d = new Date(fixed);
  return isNaN(d.getTime()) ? null : d;
}

function formatRelative(iso?: string | null): string {
  const d = parseIso(iso);
  if (!d) return "";
  const diffMs = Date.now() - d.getTime();
  const mins  = Math.floor(diffMs / 60000);
  const hours = Math.floor(diffMs / 3600000);
  const days  = Math.floor(diffMs / 86400000);
  if (mins < 1)  return "just now";
  if (mins < 60) return `${mins}m ago`;
  if (hours < 24) return `${hours}h ago`;
  return `${days}d ago`;
}

function formatTime(d: Date): string {
  // Show date if not today, otherwise just time
  const today = new Date();
  const isToday = d.getFullYear() === today.getFullYear()
               && d.getMonth()    === today.getMonth()
               && d.getDate()     === today.getDate();
  if (isToday) {
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  return d.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

// Get or create a stable conversationId in localStorage
function getOrCreateConvId(): string {
  if (typeof window === "undefined") return `conv-${Date.now()}`;
  const stored = localStorage.getItem("safety-chat-convId");
  if (stored) return stored;
  const id = `conv-${Date.now()}`;
  localStorage.setItem("safety-chat-convId", id);
  return id;
}

export default function ChatPage() {
  const [messages, setMessages]       = useState<Message[]>([WELCOME]);
  const [input, setInput]             = useState("");
  const [loading, setLoading]         = useState(false);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [conversations, setConversations] = useState<ConvMeta[]>([]);
  const [activeConvId, setActiveConvId]   = useState<string>(getOrCreateConvId);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef  = useRef<HTMLTextAreaElement>(null);

  // Scroll to bottom on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Load conversation list from MongoDB
  const loadConversations = useCallback(() => {
    fetch(`${API_BASE}/api-chat/conversations`)
      .then((r) => r.ok ? r.json() : [])
      .then((data: ConvMeta[]) => setConversations(data))
      .catch(() => {});
  }, []);

  // Load messages for the active conversation
  const loadHistory = useCallback((convId: string) => {
    setHistoryLoaded(false);
    setMessages([WELCOME]);
    fetch(`${API_BASE}/api-chat/history/${convId}`)
      .then((r) => r.ok ? r.json() : [])
      .then((turns: { role: string; content: string; timestamp?: string }[]) => {
        if (turns.length > 0) {
          const loaded: Message[] = turns.map((t) => ({
            role:      t.role as "user" | "assistant",
            content:   t.content,
            timestamp: parseIso(t.timestamp) ?? new Date(),
          }));
          setMessages([WELCOME, ...loaded]);
        }
      })
      .catch(() => {})
      .finally(() => setHistoryLoaded(true));
  }, []);

  // On mount: load list + active conversation history
  useEffect(() => {
    loadConversations();
    loadHistory(activeConvId);
  }, [activeConvId, loadConversations, loadHistory]);

  // Switch to a different conversation
  const handleSwitchConv = (convId: string) => {
    localStorage.setItem("safety-chat-convId", convId);
    setActiveConvId(convId);
  };

  // Start a fresh conversation
  const handleNewChat = () => {
    const id = `conv-${Date.now()}`;
    localStorage.setItem("safety-chat-convId", id);
    setActiveConvId(id);
    setMessages([WELCOME]);
    setHistoryLoaded(true);
  };

  const handleSend = async (query?: string) => {
    const text = (query ?? input).trim();
    if (!text || loading) return;

    setMessages((prev) => [...prev, { role: "user", content: text, timestamp: new Date() }]);
    setInput("");
    setLoading(true);

    try {
      const res = await sendChat(text, activeConvId);
      setMessages((prev) => [
        ...prev,
        {
          role:        "assistant",
          content:     res.answer || "No response received.",
          queryType:   res.queryType,
          sourcesUsed: res.sourcesUsed || [],
          timestamp:   new Date(),
          error:       res.queryType === "error",
          imageUrl:    res.imageUrl,
          audioUrl:    res.audioUrl,
          infraredUrl: res.infraredUrl,
        },
      ]);
      // Refresh the conversation list so new title appears
      loadConversations();
    } catch {
      setMessages((prev) => [
        ...prev,
        {
          role:      "assistant",
          content:   "Could not reach the Akka backend. Make sure the safety agent is running on port 8080.",
          timestamp: new Date(),
          error:     true,
        },
      ]);
    } finally {
      setLoading(false);
      inputRef.current?.focus();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
  };

  const queryTypeIcon = (type?: string) => {
    if (type === "rag")       return <Database    size={11} className="text-purple-400" />;
    if (type === "sensor")    return <Search      size={11} className="text-blue-400" />;
    if (type === "escalation")return <AlertTriangle size={11} className="text-orange-400" />;
    return null;
  };

  return (
    <div className="flex h-[calc(100vh-3rem)] gap-0 overflow-hidden rounded-xl border border-gray-800">

      {/* ── Left sidebar: conversation list ── */}
      <div className="w-60 flex-shrink-0 bg-gray-900 border-r border-gray-800 flex flex-col">
        {/* Sidebar header */}
        <div className="px-3 py-3 border-b border-gray-800 flex items-center justify-between">
          <span className="text-xs font-semibold text-gray-400 uppercase tracking-widest">Chats</span>
          <button
            onClick={handleNewChat}
            title="New conversation"
            className="w-6 h-6 flex items-center justify-center rounded-md bg-gray-800 hover:bg-gray-700 text-gray-400 hover:text-white transition-colors"
          >
            <Plus size={13} />
          </button>
        </div>

        {/* Conversation list */}
        <div className="flex-1 overflow-y-auto py-1">
          {conversations.length === 0 ? (
            <div className="px-3 py-6 text-center text-xs text-gray-600">
              No saved conversations yet.
              <br />Start chatting to begin.
            </div>
          ) : (
            conversations.map((conv) => (
              <button
                key={conv.conversationId}
                onClick={() => handleSwitchConv(conv.conversationId)}
                className={`w-full text-left px-3 py-2.5 hover:bg-gray-800/70 transition-colors group ${
                  activeConvId === conv.conversationId ? "bg-gray-800" : ""
                }`}
              >
                <div className={`text-xs font-medium truncate ${
                  activeConvId === conv.conversationId ? "text-white" : "text-gray-300"
                }`}>
                  {conv.title}
                </div>
                <div className="flex items-center gap-1 mt-0.5 text-[10px] text-gray-600">
                  <Clock size={9} />
                  {formatRelative(conv.lastActivity)}
                </div>
              </button>
            ))
          )}
        </div>

        {/* Footer */}
        <div className="px-3 py-2 border-t border-gray-800">
          <div className="text-[10px] text-gray-600">Powered by Mistral · MongoDB</div>
        </div>
      </div>

      {/* ── Right panel: messages + input ── */}
      <div className="flex-1 flex flex-col overflow-hidden bg-gray-950">
        {/* Header */}
        <div className="px-4 py-3 border-b border-gray-800 flex items-center gap-3 bg-gray-900">
          <div className="w-7 h-7 bg-purple-600/20 border border-purple-600/30 rounded-lg flex items-center justify-center">
            <MessageSquare size={14} className="text-purple-400" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-semibold text-white truncate">
              {conversations.find((c) => c.conversationId === activeConvId)?.title ?? "New Conversation"}
            </div>
            <div className="text-[10px] text-gray-500">
              Grounded on live sensor data · persistent history
            </div>
          </div>
        </div>

        {/* Suggested queries — fresh conversation only */}
        {historyLoaded && messages.length === 1 && (
          <div className="px-4 pt-4 pb-2 flex flex-wrap gap-2">
            {SUGGESTED_QUERIES.map((q) => (
              <button
                key={q}
                onClick={() => handleSend(q)}
                className="text-xs px-3 py-1.5 bg-gray-800 hover:bg-gray-700 border border-gray-700 hover:border-gray-600 rounded-full text-gray-400 hover:text-white transition-colors"
              >
                {q}
              </button>
            ))}
          </div>
        )}

        {/* Message thread */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
          {messages.map((msg, i) => (
            <div key={i} className={`flex gap-3 ${msg.role === "user" ? "flex-row-reverse" : ""}`}>
              {/* Avatar */}
              <div className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5 ${
                msg.role === "user"
                  ? "bg-blue-600/20 border border-blue-600/30"
                  : msg.error
                  ? "bg-red-600/20 border border-red-600/30"
                  : "bg-purple-600/20 border border-purple-600/30"
              }`}>
                {msg.role === "user"
                  ? <User size={13} className="text-blue-400" />
                  : <Bot  size={13} className={msg.error ? "text-red-400" : "text-purple-400"} />
                }
              </div>

              {/* Bubble */}
              <div className={`max-w-[78%] flex flex-col gap-1 ${msg.role === "user" ? "items-end" : "items-start"}`}>
                <div className={`px-3.5 py-2.5 rounded-2xl text-sm leading-relaxed ${
                  msg.role === "user"
                    ? "bg-blue-600/25 border border-blue-600/30 text-white rounded-tr-sm"
                    : msg.error
                    ? "bg-red-900/20 border border-red-800/40 text-red-300 rounded-tl-sm"
                    : "bg-gray-800 border border-gray-700/60 text-gray-200 rounded-tl-sm"
                }`}>
                  {msg.content}
                  {/* Media attachments — only on assistant messages */}
                  {msg.role === "assistant" && (msg.imageUrl || msg.infraredUrl || msg.audioUrl) && (
                    <div className="mt-3 pt-3 border-t border-gray-700/60 space-y-2">
                      {/* Cameras row */}
                      {(msg.infraredUrl || msg.imageUrl) && (
                        <div className="grid grid-cols-2 gap-2">
                          {msg.infraredUrl && (
                            <div className="space-y-1">
                              <p className="text-[10px] text-gray-500 uppercase tracking-wide">IR Video</p>
                              <video
                                src={`${API_BASE}${msg.infraredUrl}`}
                                autoPlay muted loop playsInline
                                className="w-full rounded-lg border border-gray-700/60 object-contain bg-black"
                                style={{ maxHeight: 140 }}
                              />
                            </div>
                          )}
                          {msg.imageUrl && (
                            <div className="space-y-1">
                              <p className="text-[10px] text-gray-500 uppercase tracking-wide">Visual Camera</p>
                              <img
                                src={`${API_BASE}${msg.imageUrl}`}
                                alt="inspection"
                                className="w-full rounded-lg border border-gray-700/60 object-contain bg-black"
                                style={{ maxHeight: 140 }}
                              />
                            </div>
                          )}
                        </div>
                      )}
                      {/* Audio player */}
                      {msg.audioUrl && (
                        <div className="space-y-1">
                          <p className="text-[10px] text-gray-500 uppercase tracking-wide">Audio Recording</p>
                          <audio
                            controls
                            src={`${API_BASE}${msg.audioUrl}`}
                            className="w-full h-7"
                            style={{ colorScheme: "dark" }}
                          />
                        </div>
                      )}
                    </div>
                  )}
                </div>
                {/* Metadata */}
                <div className={`flex items-center gap-2 px-1 ${msg.role === "user" ? "flex-row-reverse" : ""}`}>
                  <span className="text-[10px] text-gray-600">{formatTime(msg.timestamp)}</span>
                  {msg.queryType && msg.queryType !== "greeting" && msg.queryType !== "error" && (
                    <span className="flex items-center gap-1 text-[10px] text-gray-600">
                      {queryTypeIcon(msg.queryType)}
                      {msg.queryType}
                    </span>
                  )}
                  {msg.sourcesUsed && msg.sourcesUsed.length > 0 && (
                    <span className="text-[10px] text-gray-600">
                      {msg.sourcesUsed.length} source{msg.sourcesUsed.length > 1 ? "s" : ""}
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}

          {/* Loading indicator */}
          {loading && (
            <div className="flex gap-3">
              <div className="w-7 h-7 rounded-lg bg-purple-600/20 border border-purple-600/30 flex items-center justify-center mt-0.5">
                <Bot size={13} className="text-purple-400" />
              </div>
              <div className="px-3.5 py-2.5 rounded-2xl rounded-tl-sm bg-gray-800 border border-gray-700/60 flex items-center gap-2">
                <Loader2 size={13} className="text-purple-400 animate-spin" />
                <span className="text-sm text-gray-500">Thinking…</span>
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Input bar */}
        <div className="px-4 pb-4 pt-2 border-t border-gray-800 bg-gray-900">
          <div className="flex items-end gap-2 bg-gray-800 border border-gray-700 rounded-xl px-3 py-2">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask about sensors, hazards, incidents… (Enter to send)"
              rows={1}
              className="flex-1 bg-transparent text-sm text-white placeholder-gray-600 resize-none outline-none max-h-28 leading-relaxed"
              style={{ fieldSizing: "content" } as React.CSSProperties}
            />
            <button
              onClick={() => handleSend()}
              disabled={!input.trim() || loading}
              className="w-7 h-7 bg-purple-600 hover:bg-purple-500 disabled:bg-gray-700 disabled:cursor-not-allowed rounded-lg flex items-center justify-center flex-shrink-0 transition-colors"
            >
              <Send size={13} className="text-white" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
