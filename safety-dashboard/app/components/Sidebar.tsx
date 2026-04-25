// safety-dashboard/app/components/Sidebar.tsx
"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Activity, FileText, MessageSquare, Shield,
  ChevronLeft, ChevronRight, Zap, Camera,
} from "lucide-react";
import { useState, useEffect } from "react";

const NAV_ITEMS = [
  { href: "/",          label: "Dashboard",          icon: Activity },
  { href: "/incidents", label: "Incidents & Reports", icon: FileText },
  { href: "/chat",      label: "Chat",               icon: MessageSquare },
  { href: "/fault",     label: "Fault Tolerance",    icon: Zap },
];

const API_BASE = "http://localhost:8080";

export default function Sidebar() {
  const pathname  = usePathname();
  const [collapsed, setCollapsed] = useState(false);
  const [nodeA,   setNodeA]   = useState(false);
  const [nodeD,   setNodeD]   = useState(false);
  const [inspCount, setInspCount] = useState(0);

  useEffect(() => {
    const poll = async () => {
      // Node A alive = HTTP server responds
      try {
        const r = await fetch(`${API_BASE}/api-status`, { cache: "no-store" });
        setNodeA(r.ok);
      } catch { setNodeA(false); }

      // Node D alive = inspection events are flowing
      try {
        const r = await fetch(`${API_BASE}/api-inspection/events`, { cache: "no-store" });
        if (r.ok) {
          const ev: unknown[] = await r.json();
          setInspCount(ev.length);
          setNodeD(ev.length > 0);
        }
      } catch { /* not yet started */ }
    };
    poll();
    const id = setInterval(poll, 5000);
    return () => clearInterval(id);
  }, []);

  // Node A is polled directly (HTTP responds = alive).
  // Node D is polled directly (inspection events flowing = alive).
  // Nodes B and C run on a separate LAN machine and cannot be probed from the
  // browser, so they always show as offline — the user will observe them via
  // their own machine's Akka logs.
  const nodes = [
    { label: "Node A", sub: "Sensors · HTTP",                      alive: nodeA,  port: "2551" },
    { label: "Node B", sub: "Retrieval · LLM  (peer)",             alive: true,   port: "2552" },
    { label: "Node C", sub: "Orchestrator  (peer)",                alive: true,   port: "2553" },
    { label: "Node D", sub: `Visual Inspection · ${inspCount} ev`, alive: nodeD,  port: "2554", icon: Camera },
  ];

  return (
    <aside
      className={`${
        collapsed ? "w-16" : "w-64"
      } bg-gray-900 border-r border-gray-800 flex flex-col transition-all duration-300 flex-shrink-0`}
    >
      {/* ── Logo ── */}
      <div className="p-4 border-b border-gray-800 flex items-center gap-3">
        <div className="w-8 h-8 bg-red-600 rounded-lg flex items-center justify-center flex-shrink-0">
          <Shield size={18} className="text-white" />
        </div>
        {!collapsed && (
          <div>
            <h1 className="text-sm font-bold text-white">Safety Monitor</h1>
            <p className="text-[10px] text-gray-500">Gas · Thermal · Visual Inspection</p>
          </div>
        )}
      </div>

      {/* ── Nav links ── */}
      <nav className="flex-1 p-3 space-y-1">
        {NAV_ITEMS.map((item) => {
          const isActive = pathname === item.href;
          const Icon     = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors ${
                isActive
                  ? "bg-red-600/20 text-red-400 border border-red-600/30"
                  : "text-gray-400 hover:bg-gray-800 hover:text-white"
              }`}
            >
              <Icon size={18} />
              {!collapsed && <span>{item.label}</span>}
            </Link>
          );
        })}
      </nav>

      {/* ── Cluster node status ── */}
      <div className={`border-t border-gray-800 ${collapsed ? "p-2" : "p-4"}`}>
        {!collapsed && (
          <div className="text-[9px] text-gray-600 uppercase tracking-widest mb-2 font-semibold">
            Cluster Nodes
          </div>
        )}
        <div className={`space-y-${collapsed ? "3" : "2"}`}>
          {nodes.map((node) => (
            <div key={node.label} className={`flex items-start ${collapsed ? "justify-center" : "gap-2"}`}>
              {/* Status dot */}
              <div
                title={`${node.label} — ${node.alive ? "online" : "offline"}`}
                className={`w-2 h-2 rounded-full flex-shrink-0 mt-${collapsed ? "0" : "0.5"} ${
                  node.alive ? "bg-green-500 animate-pulse" : "bg-gray-700"
                }`}
              />
              {!collapsed && (
                <div className="min-w-0">
                  <div className={`text-xs flex items-center gap-1 ${node.alive ? "text-gray-300" : "text-gray-600"}`}>
                    {node.icon && <node.icon size={10} className="text-purple-400 flex-shrink-0" />}
                    {node.label}
                    <span className="text-gray-700 font-mono text-[9px] ml-0.5">:{node.port}</span>
                  </div>
                  <div className="text-[9px] text-gray-600 truncate">{node.sub}</div>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* ── Collapse toggle ── */}
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="p-3 border-t border-gray-800 text-gray-500 hover:text-white flex justify-center"
      >
        {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
      </button>
    </aside>
  );
}
