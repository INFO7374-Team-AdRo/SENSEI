# SENSEI: Sensor Safety Engineering Intelligence

An industrial safety monitoring platform that combines simulated gas-sensor streams, thermal analysis, visual inspection events, retrieval-augmented incident history, and an operator dashboard.

The backend is a Java 17 Akka Typed system. The frontend is a Next.js dashboard that polls the Akka HTTP server for live telemetry, incidents, chat history, and fault-tolerance state.

## What The Project Does

- Replays industrial sensor data from `data/Gas_Sensors_Measurements.csv`.
- Classifies gas hazards with a PMML model in `src/main/resources/gas_classifier.pmml`.
- Fuses gas, thermal, and optional audio signals into incident candidates.
- Uses Mistral for reasoning, report generation, embeddings, and visual analysis.
- Stores incident history in MongoDB.
- Stores vector embeddings for incident retrieval in Qdrant.
- Replays InspecSafe visual inspection events from `inspecsafev1/`.
- Serves a dashboard, incident reports, chat assistant, and shard-failure demo over HTTP.

## Repository Layout

```text
.
|-- src/main/java/com/safety
|   |-- SafetyGuardian.java          # main entry point and node-role bootstrap
|   |-- agents/                      # Akka actors for sensing, fusion, reasoning, orchestration
|   |-- clients/                     # MongoDB, Qdrant, Mistral, email clients
|   |-- http/                        # Akka HTTP API server
|   `-- protocol/                    # typed message contracts
|-- src/main/resources
|   |-- application.conf             # runtime config and environment overrides
|   `-- gas_classifier.pmml          # gas classification model
|-- data/
|   `-- Gas_Sensors_Measurements.csv # replay source for MQ sensor data
|-- inspecsafev1/                    # visual inspection dataset
|-- safety-dashboard/                # Next.js operator UI
`-- pom.xml                          # Maven build
```

## Architecture

### Backend pipeline

1. `DataReplayStream` replays CSV rows and feeds Akka Cluster Sharding entities for each MQ sensor.
2. `SensorAgent` instances normalize readings and forward them to `FusionAgent`.
3. `ThermalAgent` adds thermal context, including image-based analysis when available.
4. `FusionAgent` builds a fused event window and sends it to `ClassificationAgent`.
5. `ClassificationAgent` runs the PMML model and predicts the hazard class.
6. `LLMReasoningAgent` generates hazard reasoning and recommendations through Mistral.
7. `EscalationAgent` assigns a tier and forwards the finalized incident to `OrchestratorAgent`.
8. `OrchestratorAgent` persists incidents to MongoDB and stores retrievable summaries in Qdrant through `RetrievalAgent`.
9. `SafetyHttpServer` exposes live state and historical data to the dashboard and chat UI.

### Visual inspection pipeline

1. `VisualInspectionAgent` replays waypoint events from `inspecsafev1/`.
2. `InspectionReceiverActor` delivers inspection events into the main pipeline.
3. `SafetyHttpServer` stores recent visual events in memory and serves related image, audio, and IR assets.
4. Grade 1 and Grade 2 inspection events trigger LLM-generated reports and vector storage for future chat retrieval.

### Deployment modes

The system can run as either:

- `all`: a single-node local setup for development and demos.
- `node-a`: sensor sharding, thermal analysis, fusion, classification, HTTP server.
- `node-b`: retrieval, LLM reasoning, escalation.
- `node-c`: orchestrator, MongoDB persistence, HTTP server.
- `node-d`: visual inspection replay.

Set the mode with `SAFETY_ROLE`. If unset, the backend defaults to `all`.

## Tech Stack

- Java 17
- Maven
- Akka Typed, Akka Cluster, Akka HTTP
- MongoDB
- Qdrant
- Mistral APIs
- Next.js 16
- React 19
- Tailwind CSS 4
- Recharts

## Configuration

Primary runtime configuration lives in [src/main/resources/application.conf](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/src/main/resources/application.conf).

The code supports environment-variable overrides for the most important settings:

```powershell
$env:SAFETY_ROLE="all"
$env:AKKA_HOST="127.0.0.1"
$env:AKKA_PORT="2551"
$env:MONGODB_URI="mongodb://localhost:27017"
$env:QDRANT_URL="http://localhost:6333"
$env:QDRANT_API_KEY=""
$env:MISTRAL_API_KEY="your-key"
$env:INSPECSAFE_DATA_PATH=".\inspecsafev1"
```

Important config groups:

- `safety.http`: backend host and port, default `0.0.0.0:8080`
- `safety.mongodb`: incident persistence
- `safety.qdrant`: vector search storage
- `safety.mistral`: chat, reasoning, vision, and embedding models
- `safety.data`: CSV replay source and interval
- `safety.inspecsafe`: visual dataset path and replay interval
- `safety.model`: PMML model path
- `safety.email`: SMTP settings for escalation notifications

## Security Note

`application.conf` currently contains concrete service credentials and email settings in the checked-in file. For shared or production use, move those values into environment variables or a non-committed secrets file and rotate any exposed keys.

## Prerequisites

- Java 17+
- Maven 3.9+
- Node.js 20+
- npm
- Access to MongoDB
- Access to Qdrant
- Access to Mistral APIs

## Running The Project

### 1. Start the backend

From the repository root:

```powershell
mvn compile
mvn exec:java
```

The backend starts `com.safety.SafetyGuardian` and serves HTTP on `http://localhost:8080` by default.

### 2. Start the dashboard

From [safety-dashboard](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard):

```powershell
npm install
npm run dev
```

The dashboard runs on `http://localhost:3000`.

### 3. Open the UI

- Dashboard: `http://localhost:3000`
- Backend API: `http://localhost:8080`

## Building

### Backend jar

```powershell
mvn package -DskipTests
java -jar target/safety-agent.jar
```

### Frontend production build

```powershell
cd safety-dashboard
npm run build
npm run start
```

## HTTP API Overview

The backend HTTP surface is implemented in [src/main/java/com/safety/http/SafetyHttpServer.java](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/src/main/java/com/safety/http/SafetyHttpServer.java).

Core endpoints:

- `GET /health`: basic health check
- `GET /api-events`: combined dashboard payload with latest sensor, thermal, status, history, escalations, and inspection events
- `GET /api-sensors`: recent sensor events
- `GET /api-sensor-history`: rolling sensor history for charts
- `GET /api-escalations`: recent T2/T3 escalation feed
- `GET /api-thermal`: current thermal state and active frame metadata
- `POST /api-thermal/analyze`: submit a thermal image for analysis
- `GET /api-status`: aggregate system status
- `GET /api-incidents`: incident list
- `GET /api-report/{incidentId}`: full incident report
- `GET /api-chat/conversations`: saved chat sessions
- `GET /api-chat/history/{conversationId}`: prior messages for a session
- `POST /api-chat`: grounded operator chat query
- `GET /api-inspection/events`: recent visual inspection events
- `GET /api-inspection/image/{path}`: inspection image asset
- `GET /api-inspection/audio/{path}`: inspection audio asset
- `GET /api-inspection/video/{path}`: inspection IR video asset
- `GET /api-fault/status`: shard fault-demo state
- `POST /api-fault/kill/{sensorType}`: intentionally stop a sensor shard

## Frontend Pages

The Next.js app in [safety-dashboard](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/safety-dashboard) provides:

- `/`: real-time dashboard for gas, thermal, and visual monitoring
- `/incidents`: incident log and generated reports
- `/chat`: grounded chat assistant over live state and past incidents
- `/fault`: Akka shard failure and recovery demonstration

## Fault Tolerance

The project includes an explicit Akka fault-tolerance demo:

- Sensor entities are sharded by MQ sensor type.
- `POST /api-fault/kill/{sensorType}` stops a shard entity on purpose.
- Akka Cluster Sharding recreates the entity on the next message.
- The dashboard shows `killed`, `recovering`, and `alive` transitions.

## Development Notes

- The frontend assumes the backend is reachable at `http://localhost:8080`.
- The backend uses in-memory rolling buffers for live events and external stores for persistence/retrieval.
- Qdrant failures degrade gracefully by disabling RAG until the next successful reconnect.
- `dependency-reduced-pom.xml` is generated by the shade plugin during packaging.
