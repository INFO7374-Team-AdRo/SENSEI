# SENSEI — Industrial Safety Monitoring

CSYE 7374 final project — a multi-agent streaming pipeline that ingests gas-sensor + thermal data, classifies hazards via ONNX, retrieves similar past incidents from Qdrant (RAG), explains them with Mistral, and routes through a tiered escalation policy. Synthesized from two parallel implementations of the same problem statement to satisfy every required constraint.

## Constraints satisfied

| Requirement | How it is met |
|---|---|
| Akka messaging pattern | Typed actors, message adapters, AskPattern, Receptionist |
| Akka sharding | `SensorAgent` is one entity per sensor type (MQ2..MQ135), 7 shards |
| Database persistence | MongoDB (incident archive) + Akka Persistence journal (sensor events) |
| AI integration (API based) | Mistral chat (`mistral-small-latest`) + embeddings (`mistral-embed`) |
| Akka cluster | 3-node cluster (artery 2551/2552/2553) with role-based agent placement |
| Active persistence | `SensorAgent` is `EventSourcedBehavior`; replay rebuilds rolling state |
| Communication between actors | Receptionist for cross-node discovery, ServiceKeys, message adapters |
| Fault tolerance | `SupervisorStrategy.restartWithBackoff`, sharded auto-rehydrate, SBR |
| Sharded cluster | Cluster Sharding entity proxy on every node, shards keyed by sensor type |

## Architecture

```
Node A (ingestion + fusion)        Node B (reasoning + escalation)     Node C (orchestrator + dashboard)
  ThermalAgent                       RetrievalAgent (Qdrant)             OrchestratorAgent
  FusionAgent                        LLMReasoningAgent (Mistral)         SafetyHttpServer (8080)
  ClassificationAgent (ONNX)         EscalationAgent                     MongoDBService
  SensorAgent shards (×7)            SensorAgent shards (×7)             SensorAgent shards (×7)
```

Sensor sharding runs on every node (proxy-routed). Cross-node discovery is done via the cluster Receptionist with a `ServiceKey` per agent type.

## Pipeline

1. `CsvReplaySource` streams `expanded_final_dataset.csv` at 1 row / 2s.
2. Each row is fanned out to 7 sharded `SensorAgent` entities (event-sourced) and to the `FusionAgent`.
3. `ThermalAgent` produces a synthetic per-tick frame.
4. `FusionAgent` waits for all 7 sensors + thermal (or a 3-second timer) and emits a `FusedSnapshot`.
5. `ClassificationAgent` runs ONNX inference (XGBoost), falls back to rule-based on failure.
6. If `NO_GAS` with high confidence, the orchestrator short-circuits and emits a no-op incident.
7. Otherwise: `RetrievalAgent` embeds the situation and pulls top-3 similar incidents from Qdrant.
8. `LLMReasoningAgent` calls Mistral with the snapshot + RAG context, returns `{analysis, recommendation, suggestedTier}`.
9. `EscalationAgent` applies the safety-first floor: confidence-tier OR LLM tier, whichever is higher.
10. Finalized `IncidentReport` is broadcast over SSE, persisted to MongoDB, and upserted back into Qdrant (self-improving RAG).

## Quick start (single JVM)

```bash
# 1. Build (Java 21, Maven)
mvn clean package -DskipTests

# 2. (Optional) start local MongoDB + Qdrant
docker compose up -d mongodb qdrant

# 3. (Optional) seed Qdrant with example incidents
export MISTRAL_API_KEY=...   # leave unset to use pseudo-random vectors
python3 scripts/seed-qdrant.py

# 4. Set integration secrets (all optional — fallbacks used when missing)
export MISTRAL_API_KEY=...
export MONGODB_URI="mongodb://localhost:27017"   # or Atlas URI
export QDRANT_URL="http://localhost:6333"        # or Qdrant Cloud URL
export QDRANT_API_KEY=...                        # only for Qdrant Cloud

# 5. Run
./scripts/run-demo.sh
# Dashboard: http://localhost:8080/dashboard/index.html
```

## Quick start (3-node cluster)

```bash
mvn clean package -DskipTests
./scripts/start-cluster.sh    # logs in node-{a,b,c}.log
./scripts/stop-cluster.sh
```

Same env vars apply. For multi-host deploy set `CLUSTER_HOST_A` / `CLUSTER_HOST_BC` so the seed-node URIs resolve correctly.

## HTTP surface

| Endpoint | Purpose |
|---|---|
| `GET /events` | Server-sent stream of `IncidentReport` JSON (live dashboard) |
| `GET /health` | Liveness probe |
| `GET /api-status` | Counters + Mongo/Reasoning availability |
| `GET /api-incidents` | Finalized incidents (Mongo primary, in-memory fallback via AskPattern) |
| `GET /api-report/{id}` | Single incident detail |
| `POST /api-chat` | NL query → LLMReasoningAgent (`{query, conversationId}`) |
| `GET /api-fault/status` | Per-shard alive / killed / recovering |
| `POST /api-fault/kill/{sensorType}` | Stop a sensor shard — sharding rehydrates on next message |
| `GET /dashboard/*` | Static dashboard files |

## Fault tolerance demo

1. Open the dashboard, watch live incidents.
2. Click a sensor in the **Fault Tolerance Demo** panel (or `POST /api-fault/kill/MQ2`).
3. The shard stops immediately. Shard status flips `killed` → `recovering` → `alive` within ~2 seconds.
4. The next sensor reading triggers Akka Cluster Sharding to reincarnate the entity; Akka Persistence replays the event journal so rolling state is restored.
5. Incidents continue without operator intervention.

## ML model

Pre-trained ONNX classifier at `src/main/resources/gas_classifier.onnx`. Retrain with:

```bash
cd ml
pip install -r requirements.txt
python preprocess.py
python train_classifier.py
python evaluate.py
```

Output is XGBoost → ONNX 4-class softmax over `{NO_GAS, SMOKE, PERFUME, COMBINED}`.

## Project layout

```
SENSEI/
├── pom.xml                    # Maven (Java 21, Akka 2.8.6, akka-http 10.5.3)
├── docker-compose.yml         # MongoDB + Qdrant local
├── scripts/                   # start/stop cluster, seed Qdrant, run-demo
├── ml/                        # XGBoost training pipeline + ONNX export
├── dashboard/                 # Vanilla HTML/CSS/JS, SSE client + chat + fault demo
└── src/main/
    ├── java/edu/neu/safety/
    │   ├── Main.java
    │   ├── model/             # CborSerializable records (over-the-wire types)
    │   ├── agents/            # 8 typed actors with their protocols
    │   ├── cluster/           # ClusterSetup, ClusterRoles, AgentServiceKeys
    │   ├── inference/         # ONNX classifier + feature extractor
    │   ├── external/          # MistralClient, QdrantService, MongoDBService
    │   ├── replay/            # CSV + thermal frame loaders
    │   └── http/              # SafetyHttpServer (SSE + REST)
    └── resources/
        ├── application.conf, single-jvm.conf, node-{a,b,c}.conf
        ├── logback.xml
        └── gas_classifier.onnx
```
