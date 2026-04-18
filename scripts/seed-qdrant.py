#!/usr/bin/env python3
"""
Seed Qdrant with sample past incidents for RAG retrieval.

Reads QDRANT_URL (default http://localhost:6333) and optional QDRANT_API_KEY
(set when targeting Qdrant Cloud). Uses Mistral Embed if MISTRAL_API_KEY is set,
otherwise generates deterministic pseudo-random vectors so the rest of the
pipeline still has something to retrieve.
"""
import os
import sys
import requests
import numpy as np

QDRANT_URL = os.environ.get("QDRANT_URL", "http://localhost:6333").rstrip("/")
QDRANT_API_KEY = os.environ.get("QDRANT_API_KEY", "")
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "incident_knowledge")
VECTOR_SIZE = int(os.environ.get("QDRANT_VECTOR_SIZE", "1024"))
MISTRAL_API_KEY = os.environ.get("MISTRAL_API_KEY", "")
MISTRAL_URL = "https://api.mistral.ai/v1/embeddings"

HEADERS = {"Content-Type": "application/json"}
if QDRANT_API_KEY:
    HEADERS["api-key"] = QDRANT_API_KEY

INCIDENTS = [
    {
        "description": "Smoke detected in Zone B near welding station. MQ2 reading at 1200 ppm, MQ7 (CO) at 45 ppm. Thermal camera showed 68C hotspot in northeast corner.",
        "resolution": "Activated zone ventilation system. Safety team dispatched. Source identified as overheated motor bearing causing friction smoke. Bearing replaced, ventilation restored normal air quality in 45 minutes."
    },
    {
        "description": "Gradual smoke buildup in electrical panel room. MQ7 spiked to 55 ppm first, followed by MQ2 rise to 800 ppm over 10 minutes. Thermal showed localized 72C hotspot.",
        "resolution": "Electrical isolation activated. Fire suppression standing by. Found smoldering wire insulation due to loose connection causing arcing. Connection repaired, room ventilated. Incident duration: 90 minutes."
    },
    {
        "description": "MQ2 and MQ8 elevated in hydrogen storage area. No thermal anomaly detected. MQ2 at 900 ppm, MQ8 at 1500 ppm. Cross-modal disagreement flagged.",
        "resolution": "Hydrogen leak from valve fitting detected. Area evacuated. Valve tightened and leak test performed. Sensors returned to baseline in 20 minutes."
    },
    {
        "description": "VOC detection near chemical storage room. MQ3 at 450 ppm, MQ135 at 350 ppm. No thermal anomaly. Classification: Perfume/VOC.",
        "resolution": "Source identified as spilled cleaning solvent (isopropyl alcohol). Area ventilated. Spill cleaned according to SDS protocol. Low hazard event."
    },
    {
        "description": "Perfume-type substance detected in break room area. MQ3 at 280 ppm, MQ135 at 180 ppm. Thermal normal at 24C.",
        "resolution": "False alarm. Source was aerosol air freshener used by staff. Sensor calibration verified. No action required beyond noting in log."
    },
    {
        "description": "CRITICAL: Combined gas and smoke event in reactor bay. All sensors elevated: MQ2=1800, MQ5=1200, MQ6=950, MQ7=62, MQ135=500. Thermal showed 85C across multiple zones.",
        "resolution": "Emergency shutdown initiated immediately. Full facility evacuation completed in 8 minutes. Chemical leak from cracked heat exchanger feeding combustion. Hazmat team deployed. Facility shut down for 12 hours for repair and air quality verification."
    },
    {
        "description": "Multi-sensor alert near furnace area. MQ2=1400, MQ7=48, MQ8=1100. Thermal maximum 78C. Classification: Combined with high confidence.",
        "resolution": "Gas supply shut off automatically via safety interlock. Fire team deployed and found cracked refractory lining causing gas bypass. Emergency repairs completed. Production resumed after 6-hour safety review."
    },
    {
        "description": "Sudden MQ7 (CO) spike to 70 ppm in enclosed workspace. Other sensors normal. Thermal showed gradual temperature rise to 45C.",
        "resolution": "Carbon monoxide from malfunctioning propane heater. Workers evacuated. Heater shut off and removed. Space ventilated to 0 ppm CO within 30 minutes. Heater replaced with electric unit."
    },
    {
        "description": "MQ5 and MQ6 elevated in fuel storage area. MQ5=1100, MQ6=900. No smoke indicators. Thermal normal.",
        "resolution": "LPG leak from storage tank valve. Isolation valve closed remotely. Hazmat team performed leak repair. Gas levels returned to normal in 45 minutes."
    },
    {
        "description": "MQ135 gradually increasing from baseline 50 to 280 ppm over 2 hours in paint shop. Other sensors stable. Thermal normal.",
        "resolution": "Inadequate ventilation during spray painting operations. Exhaust fan found to be running at reduced speed due to belt wear. Fan belt replaced, ventilation restored. Air quality improved within 1 hour."
    },
    {
        "description": "Baseline drift detected on MQ3 sensor in ambient monitoring station. Reading increasing 5 ppm/hour without source. Other sensors stable.",
        "resolution": "Sensor recalibrated per manufacturer schedule. Drift was within expected aging profile. Sensor marked for replacement at next maintenance window."
    },
    {
        "description": "Intermittent MQ2 spikes (200-500 ppm) in loading dock area. Pattern: peaks during truck arrivals. Thermal shows engine exhaust heat signatures.",
        "resolution": "Normal operational pattern from diesel truck exhaust during loading operations. Added to known pattern library. Alert threshold adjusted for loading dock zone to reduce nuisance alarms."
    },
    {
        "description": "Night shift alarm: MQ7 at 38 ppm, MQ2 at 600 ppm. Thermal anomaly at 55C in maintenance area. Low confidence classification.",
        "resolution": "Welding work by night maintenance crew. Pre-authorization for hot work was on file but sensor system not updated. Work permit system updated to automatically adjust sensor thresholds during authorized hot work."
    },
    {
        "description": "Simultaneous readings: MQ3=520, MQ135=410, thermal normal. Pattern inconsistent with typical smoke or gas leak. Classification: Perfume with moderate confidence.",
        "resolution": "Laboratory chemical analysis in progress without adequate fume hood use. Researchers reminded of ventilation protocols. Fume hood inspected and certified operational."
    },
    {
        "description": "Rapid sensor cascade: MQ7 spike first (55 ppm), followed 30 seconds later by MQ2 (1100 ppm), then thermal anomaly (63C). Pattern suggests fire development.",
        "resolution": "Early-stage fire detected in cable tray. Halon suppression activated automatically. Fire contained to 2-meter section. Damaged cables replaced. Root cause: cable overloading from unauthorized equipment addition."
    }
]


def get_embedding(text):
    if MISTRAL_API_KEY:
        try:
            resp = requests.post(
                MISTRAL_URL,
                headers={
                    "Authorization": f"Bearer {MISTRAL_API_KEY}",
                    "Content-Type": "application/json"
                },
                json={"model": "mistral-embed", "input": [text]},
                timeout=30
            )
            if resp.status_code == 200:
                return resp.json()["data"][0]["embedding"]
            print(f"  Mistral API HTTP {resp.status_code}: {resp.text[:200]}")
        except Exception as e:
            print(f"  Mistral API error: {e}")
    np.random.seed(hash(text) % (2**32))
    return np.random.randn(VECTOR_SIZE).tolist()


def create_collection():
    try:
        resp = requests.get(f"{QDRANT_URL}/collections/{COLLECTION_NAME}", headers=HEADERS, timeout=10)
        if resp.status_code == 200:
            print(f"Collection '{COLLECTION_NAME}' exists, deleting for fresh seed...")
            requests.delete(f"{QDRANT_URL}/collections/{COLLECTION_NAME}", headers=HEADERS, timeout=10)

        resp = requests.put(
            f"{QDRANT_URL}/collections/{COLLECTION_NAME}",
            headers=HEADERS,
            json={"vectors": {"size": VECTOR_SIZE, "distance": "Cosine"}},
            timeout=10
        )
        if resp.status_code in (200, 201):
            print(f"Created collection '{COLLECTION_NAME}' (size={VECTOR_SIZE}, distance=Cosine)")
            return True
        print(f"Failed to create collection: HTTP {resp.status_code}: {resp.text}")
        return False
    except requests.RequestException as e:
        print(f"ERROR connecting to Qdrant at {QDRANT_URL}: {e}")
        print("Run: docker compose up -d qdrant   (or set QDRANT_URL/QDRANT_API_KEY for cloud)")
        return False


def seed_incidents():
    print(f"\nSeeding {len(INCIDENTS)} incidents...")
    points = []
    for i, incident in enumerate(INCIDENTS):
        print(f"  [{i+1}/{len(INCIDENTS)}] {incident['description'][:60]}...")
        vector = get_embedding(incident['description'])
        points.append({
            "id": i + 1,
            "vector": vector,
            "payload": {
                "description": incident["description"],
                "resolution": incident["resolution"],
                "timestamp": 1700000000 + i * 86400
            }
        })

    resp = requests.put(
        f"{QDRANT_URL}/collections/{COLLECTION_NAME}/points",
        headers=HEADERS,
        json={"points": points},
        timeout=30
    )
    if resp.status_code in (200, 201):
        print(f"\nInserted {len(points)} incidents into Qdrant.")
    else:
        print(f"Failed to insert points: {resp.status_code}: {resp.text}")


def verify():
    resp = requests.post(
        f"{QDRANT_URL}/collections/{COLLECTION_NAME}/points/scroll",
        headers=HEADERS,
        json={"limit": 5, "with_payload": True, "with_vector": False},
        timeout=10
    )
    if resp.status_code == 200:
        points = resp.json().get("result", {}).get("points", [])
        print(f"\nVerification — found {len(points)} points (showing first 5):")
        for p in points:
            print(f"  ID {p['id']}: {p['payload']['description'][:80]}...")


def main():
    print("=== Qdrant Incident Knowledge Base Seeder ===")
    print(f"Target: {QDRANT_URL}/collections/{COLLECTION_NAME}")
    print(f"Auth  : {'api-key set' if QDRANT_API_KEY else 'no api-key (local Docker)'}")
    print(f"Embed : {'Mistral mistral-embed' if MISTRAL_API_KEY else 'pseudo-random (set MISTRAL_API_KEY for real embeddings)'}")
    print()

    if not create_collection():
        sys.exit(1)
    seed_incidents()
    verify()
    print("\nKnowledge base ready for RAG retrieval.")


if __name__ == '__main__':
    main()
