#!/bin/bash
# Start the 3-node Akka Cluster for the SENSEI safety monitoring project.
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PROJECT_DIR/target/SENSEI-1.0-SNAPSHOT-jar-with-dependencies.jar"

if [ ! -f "$JAR" ]; then
    echo "JAR not found. Building project..."
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests
fi

echo "=== Starting SENSEI Safety Cluster ==="
echo "Mistral key set: $([ -n "$MISTRAL_API_KEY" ] && echo yes || echo NO)"
echo "Qdrant URL    : ${QDRANT_URL:-http://localhost:6333}"
echo "MongoDB URI   : $([ -n "$MONGODB_URI" ] && echo configured || echo NO)"
echo ""

cd "$PROJECT_DIR"

echo "[Node A] Starting INGESTION + FUSION on artery 2551..."
java -Dconfig.resource=node-a.conf -jar "$JAR" > "$PROJECT_DIR/node-a.log" 2>&1 &
NODE_A_PID=$!
echo "[Node A] PID: $NODE_A_PID (logs: node-a.log)"
sleep 5

echo "[Node B] Starting REASONING + ESCALATION on artery 2552..."
java -Dconfig.resource=node-b.conf -jar "$JAR" > "$PROJECT_DIR/node-b.log" 2>&1 &
NODE_B_PID=$!
echo "[Node B] PID: $NODE_B_PID (logs: node-b.log)"
sleep 3

echo "[Node C] Starting ORCHESTRATOR + DASHBOARD on artery 2553..."
java -Dconfig.resource=node-c.conf -jar "$JAR" > "$PROJECT_DIR/node-c.log" 2>&1 &
NODE_C_PID=$!
echo "[Node C] PID: $NODE_C_PID (logs: node-c.log)"

echo ""
echo "=== Cluster Started ==="
echo "Node A (Ingestion + Fusion)        : PID $NODE_A_PID, port 2551"
echo "Node B (Reasoning + Escalation)    : PID $NODE_B_PID, port 2552"
echo "Node C (Orchestrator + Dashboard)  : PID $NODE_C_PID, port 2553"
echo ""
echo "Dashboard  : http://localhost:8080/dashboard/index.html"
echo "Health     : http://localhost:8080/health"
echo "SSE Events : http://localhost:8080/events"
echo "Incidents  : http://localhost:8080/api-incidents"
echo "Fault demo : POST http://localhost:8080/api-fault/kill/MQ2"
echo ""
echo "$NODE_A_PID $NODE_B_PID $NODE_C_PID" > "$PROJECT_DIR/cluster.pids"
echo "PIDs saved to $PROJECT_DIR/cluster.pids — run stop-cluster.sh to terminate"
