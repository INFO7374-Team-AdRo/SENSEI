#!/bin/bash
# End-to-end demo runner — single JVM mode, fastest path to seeing live incidents.
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PROJECT_DIR/target/SENSEI-1.0-SNAPSHOT-jar-with-dependencies.jar"

cd "$PROJECT_DIR"

if [ ! -f "$JAR" ]; then
    echo "Building project (mvn clean package)..."
    mvn clean package -DskipTests
fi

echo "=== Single-JVM Safety Demo ==="
echo "Mistral key set : $([ -n "$MISTRAL_API_KEY" ] && echo yes || echo NO — fallback used)"
echo "Qdrant URL      : ${QDRANT_URL:-http://localhost:6333}"
echo "MongoDB URI     : $([ -n "$MONGODB_URI" ] && echo configured || echo NOT configured — incidents NOT persisted)"
echo ""
echo "Dashboard will be at: http://localhost:8080/dashboard/index.html"
echo ""

exec java -Dconfig.resource=single-jvm.conf -jar "$JAR"
