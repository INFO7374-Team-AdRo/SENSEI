#!/bin/bash
# Stop the 3-node Akka Cluster.
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$PROJECT_DIR/cluster.pids"

if [ -f "$PID_FILE" ]; then
    PIDS=$(cat "$PID_FILE")
    echo "Stopping cluster nodes: $PIDS"
    for PID in $PIDS; do
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID"
            echo "  Stopped PID $PID"
        fi
    done
    rm "$PID_FILE"
else
    echo "No PID file — falling back to pkill on SafetySystem"
    pkill -f "SafetySystem" || echo "No matching processes found"
fi

echo "Cluster stopped."
