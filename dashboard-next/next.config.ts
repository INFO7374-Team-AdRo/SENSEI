import type { NextConfig } from "next";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  devIndicators: false,
  async rewrites() {
    return [
      { source: "/events", destination: `${BACKEND}/events` },
      { source: "/health", destination: `${BACKEND}/health` },
      { source: "/api-status", destination: `${BACKEND}/api-status` },
      { source: "/api-incidents", destination: `${BACKEND}/api-incidents` },
      { source: "/api-report/:id", destination: `${BACKEND}/api-report/:id` },
      { source: "/api-chat", destination: `${BACKEND}/api-chat` },
      { source: "/api-fault/status", destination: `${BACKEND}/api-fault/status` },
      { source: "/api-fault/kill/:type", destination: `${BACKEND}/api-fault/kill/:type` },
    ];
  },
};

export default nextConfig;
