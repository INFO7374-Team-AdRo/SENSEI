package com.safety.agents;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.InspectionProtocol;
import com.safety.protocol.InspectionProtocol.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * VisualInspectionAgent — InspecSafe V1 integration.
 *
 * Reads real waypoint data from the downloaded InspecSafe-V1 dataset
 * (train/Annotations/ folder) and fires InspectionEvents at a configurable
 * interval, pushing them to the SafetyHttpServer for dashboard display.
 *
 * Falls back to fully-simulated events when the dataset directory is absent
 * so the system still works before the ~3-4 GB subset is downloaded.
 *
 * Cross-modal bridge: waypoint sensor .txt files (gas/temp/humidity) are
 * surfaced alongside the visual annotation, letting the dashboard correlate
 * InspecSafe readings with the existing MQ-sensor gas stream.
 */
public class VisualInspectionAgent extends AbstractBehavior<VisualInspectionAgent.Command> {

    // ── Commands ──────────────────────────────────────────────────────────────
    public interface Command {}
    private record Tick() implements Command {}

    /**
     * Sent by SafetyGuardian when the Receptionist discovers InspectionReceiverActor
     * on node-a. Once set, events are forwarded via the actor (cross-cluster safe)
     * rather than calling SafetyHttpServer.getInstance() directly.
     */
    public record SetReceiverRef(
        akka.actor.typed.ActorRef<InspectionProtocol.ReceiverCommand> ref
    ) implements Command {}

    // ── Dataset entry loaded from disk ────────────────────────────────────────
    private record WaypointEntry(
        String      waypointId,
        String      robotId,
        String      location,
        String      imagePath,      // relative to dataset root, served via /api-inspection/image/
        String      audioPath,      // relative to dataset root, served via /api-inspection/audio/ (null if absent)
        String      infraredPath,   // relative to dataset root, served via /api-inspection/video/ (null if absent)
        String      description,
        AnomalyType anomalyType,
        Environment environment,
        File        wpDir           // absolute path to waypoint folder — used for sensor lookup
    ) {}

    // ── Simulated fallback data ───────────────────────────────────────────────
    private static final String[][] SIM_WAYPOINTS = {
        {"58132919741960", "kv-switchgear-01",    "0.4kV Switchgear Control Cabinet"},
        {"58132919741961", "conveyor-junction-02","Coal Conveyor Junction B-2"},
        {"58132919741962", "tunnel-entry-01",     "Main Tunnel Entry Gate"},
        {"58132919741963", "sintering-plant-03",  "Sintering Bed Zone 3"},
        {"58132919741964", "oil-pump-station-01", "Crude Oil Pump Station 1"},
        {"58132919741965", "power-gen-hall-02",   "Generator Hall Level 2"},
        {"58132919741966", "coal-hopper-04",      "Coal Hopper Feed Point 4"},
        {"58132919741967", "tunnel-vent-03",      "Ventilation Shaft V-3"},
        {"58132919741968", "gas-compression-01",  "Gas Compression Unit Alpha"},
        {"58132919741969", "sintering-exhaust-02","Sintering Exhaust Stack 2"},
    };

    private static final String[][] SIM_DESCRIPTIONS = {
        {"NO_HELMET",    "In the %s scenario, a worker is not wearing a safety helmet while operating near rotating equipment. Therefore the safety level is Grade One."},
        {"NO_MASK",      "In the %s scenario, personnel are not wearing respiratory protective masks in a dust-heavy zone. Therefore the safety level is Grade Two."},
        {"NO_GLOVES",    "In the %s scenario, a technician is handling electrical components without insulated gloves. Therefore the safety level is Grade Two."},
        {"FALL_DETECTED","In the %s scenario, there is a person lying on the ground near conveyor belt machinery. Therefore the safety level is Grade One."},
        {"FIRE_DETECTED","In the %s scenario, an open flame is visible adjacent to fuel storage containers. Therefore the safety level is Grade One."},
        {"SMOKING",      "In the %s scenario, personnel are smoking in a designated no-smoking flammable zone. Therefore the safety level is Grade Two."},
        {"UNAUTHORIZED_VEHICLE","In the %s scenario, a non-motorized vehicle is occupying the designated motor vehicle service lane. Therefore the safety level is Grade Three."},
        {"NORMAL",       "In the %s scenario, all personnel are compliant with PPE requirements and no hazards are detected. Normal operations confirmed."},
        {"NO_HELMET",    "In the %s scenario, two workers near the high-voltage panel are not wearing hard hats. Immediate corrective action required. Safety level is Grade One."},
        {"NORMAL",       "In the %s scenario, routine inspection confirms all safety protocols are being followed. Equipment status nominal."},
    };

    // ── State ─────────────────────────────────────────────────────────────────
    private final String datasetRoot;
    private final long intervalMs;
    private final Random rng = new Random();
    private final List<WaypointEntry> entries = new ArrayList<>();
    private int cursor = 0;
    private boolean usingRealData = false;
    // Set by SafetyGuardian when running on node-d (cross-cluster delivery)
    private akka.actor.typed.ActorRef<InspectionProtocol.ReceiverCommand> receiverRef = null;

    // ── Factory ───────────────────────────────────────────────────────────────
    public static Behavior<Command> create(String datasetRoot, long intervalMs) {
        return Behaviors.withTimers(timers ->
            Behaviors.setup(ctx -> new VisualInspectionAgent(ctx, timers, datasetRoot, intervalMs)));
    }

    private VisualInspectionAgent(ActorContext<Command> ctx,
                                   TimerScheduler<Command> timers,
                                   String datasetRoot,
                                   long intervalMs) {
        super(ctx);
        this.datasetRoot = datasetRoot;
        this.intervalMs  = intervalMs;

        loadDataset();

        // First tick after a short delay, then periodic
        timers.startTimerWithFixedDelay("inspection-tick",
            new Tick(), Duration.ofSeconds(5), Duration.ofMillis(intervalMs));

        ctx.getLog().info("VisualInspectionAgent started — realData={} entries={} interval={}ms",
            usingRealData, entries.size(), intervalMs);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Tick.class, this::onTick)
            .onMessage(SetReceiverRef.class, this::onSetReceiverRef)
            .build();
    }

    // ── Tick: fire next waypoint event ────────────────────────────────────────
    private Behavior<Command> onTick(Tick t) {
        InspectionEvent event = usingRealData ? buildFromDataset() : buildSimulated();

        if (receiverRef != null) {
            // Cross-cluster path (node-d → node-a): send via actor ref
            receiverRef.tell(new InspectionProtocol.ForwardInspectionEvent(event));
        } else {
            // Same-JVM path (all-in-one mode): call HTTP server directly
            SafetyHttpServer http = SafetyHttpServer.getInstance();
            if (http != null) http.pushInspectionEvent(event);
        }

        getContext().getLog().info("InspecSafe waypoint fired — {} {} grade={} via={}",
            event.environment().label, event.anomalyType().label,
            event.safetyGrade().label, receiverRef != null ? "actor" : "direct");
        return this;
    }

    // ── Receiver ref update (arrives from Receptionist via SafetyGuardian) ───
    private Behavior<Command> onSetReceiverRef(SetReceiverRef msg) {
        this.receiverRef = msg.ref();
        getContext().getLog().info("InspectionReceiverActor discovered at {} — switching to cross-cluster delivery",
            msg.ref().path());
        return this;
    }

    // ── Dataset loading ───────────────────────────────────────────────────────
    private void loadDataset() {
        File root = new File(datasetRoot);
        if (!root.exists() || !root.isDirectory()) {
            getContext().getLog().warn(
                "InspecSafe dataset not found at '{}' — using simulated fallback.",
                datasetRoot);
            buildSimulatedEntries();
            return;
        }

        // Scan all known splits: train, test, val — merge into one pool
        // Also handle datasets that have Annotations/ directly at root (no split subfolder)
        String[] splits = {"train", "test", "val", ""};
        for (String split : splits) {
            File annotationsDir = split.isEmpty()
                ? new File(root, "Annotations")
                : new File(root, split + "/Annotations");
            if (!annotationsDir.exists()) continue;

            int before = entries.size();
            loadAnnotationFolder(new File(annotationsDir, "Normal_data"),  false);
            loadAnnotationFolder(new File(annotationsDir, "Anomaly_data"), true);
            int added = entries.size() - before;
            if (added > 0) {
                getContext().getLog().info("Split '{}': loaded {} waypoints", split.isEmpty() ? "root" : split, added);
            }
        }

        if (entries.isEmpty()) {
            getContext().getLog().warn("No waypoints loaded from any split — using simulated fallback");
            buildSimulatedEntries();
            return;
        }

        Collections.shuffle(entries, rng);
        usingRealData = true;
        getContext().getLog().info("InspecSafe total: {} waypoints merged from all splits in '{}'",
            entries.size(), datasetRoot);
    }

    private void loadAnnotationFolder(File dir, boolean isAnomaly) {
        if (dir == null || !dir.exists()) return;
        File[] waypointDirs = dir.listFiles(File::isDirectory);
        if (waypointDirs == null) return;

        for (File wpDir : waypointDirs) {
            try {
                WaypointEntry entry = parseWaypointDir(wpDir, isAnomaly);
                if (entry != null) entries.add(entry);
            } catch (Exception e) {
                getContext().getLog().debug("Skipping waypoint dir {}: {}", wpDir.getName(), e.getMessage());
            }
        }
    }

    private WaypointEntry parseWaypointDir(File wpDir, boolean isAnomaly) throws IOException {
        String dirName = wpDir.getName();

        // Find the jpg image
        File[] jpgs = wpDir.listFiles(f -> f.getName().toLowerCase().endsWith(".jpg"));
        if (jpgs == null || jpgs.length == 0) return null;
        File imageFile = jpgs[0];

        // Find description .txt — exclude sensor files (they contain "_sensor_")
        File[] txts = wpDir.listFiles(f ->
            f.getName().toLowerCase().endsWith(".txt") &&
            !f.getName().toLowerCase().contains("_sensor_"));
        String description = "";
        if (txts != null && txts.length > 0) {
            description = Files.readString(txts[0].toPath()).trim();
        }

        // Derive anomaly type from dir name or description
        AnomalyType anomalyType = deriveAnomalyType(dirName, description, isAnomaly);

        // Derive environment from description or dir name
        Environment env = deriveEnvironment(description, dirName);

        // Parse robot MAC and location from dir name: {MAC}_{timestamp}_{location}
        String robotId  = "ROBOT-UNKNOWN";
        String location = dirName;
        String[] parts = dirName.split("_", 3);
        if (parts.length >= 1 && parts[0].matches("\\d+")) {
            robotId = formatMac(parts[0]);
        }
        if (parts.length >= 3) {
            location = parts[2].replace("-", " ").replace("_", " ");
        }

        // Image path relative to dataset root — used by HTTP server to serve file
        String datasetAbsPath = new File(datasetRoot).getAbsolutePath();
        String relativePath = imageFile.getAbsolutePath()
            .replace(datasetAbsPath, "")
            .replace("\\", "/");
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

        // Audio WAV path — look in Other_modalities/<waypointId>/
        String audioRelPath = null;
        File audioFile = findAudioFile(dirName);
        if (audioFile != null) {
            audioRelPath = audioFile.getAbsolutePath()
                .replace(datasetAbsPath, "")
                .replace("\\", "/");
            if (audioRelPath.startsWith("/")) audioRelPath = audioRelPath.substring(1);
        }

        // Infrared MP4 path — thermal-equivalent IR stream for this waypoint
        String infraredRelPath = null;
        File infraredFile = findInfraredFile(dirName);
        if (infraredFile != null) {
            infraredRelPath = infraredFile.getAbsolutePath()
                .replace(datasetAbsPath, "")
                .replace("\\", "/");
            if (infraredRelPath.startsWith("/")) infraredRelPath = infraredRelPath.substring(1);
        }

        return new WaypointEntry(dirName, robotId, location, relativePath, audioRelPath, infraredRelPath,
            description, anomalyType, env, wpDir);
    }

    // ── Event builders ────────────────────────────────────────────────────────
    private InspectionEvent buildFromDataset() {
        if (entries.isEmpty()) return buildSimulated();
        WaypointEntry wp = entries.get(cursor % entries.size());
        cursor++;

        SafetyGrade grade = gradeFor(wp.anomalyType());

        // Try to load sensor data — check the waypoint's own directory first,
        // then fall back to the standard Other_modalities tree
        Double co = null, ch4 = null, o2 = null, h2s = null, temp = null, hum = null, sound = null;
        File sensorFile = findSensorFile(wp.wpDir(), wp.waypointId());
        if (sensorFile != null) {
            double[] r = parseSensorFile(sensorFile);
            if (r != null) {
                // [0]=CO  [1]=CH4  [2]=O2  [3]=H2S  [4]=temp  [5]=hum  [6]=sound(NaN)
                co    = Double.isNaN(r[0]) ? null : r[0];
                ch4   = Double.isNaN(r[1]) ? null : r[1];
                o2    = Double.isNaN(r[2]) ? null : r[2];
                h2s   = Double.isNaN(r[3]) ? null : r[3];
                temp  = Double.isNaN(r[4]) ? null : r[4];
                hum   = Double.isNaN(r[5]) ? null : r[5];
                sound = Double.isNaN(r[6]) ? null : r[6];
            }
        }

        return new InspectionEvent(
            wp.waypointId(), wp.robotId(), wp.location(),
            wp.environment(), wp.anomalyType(), grade,
            wp.description().isEmpty() ? defaultDescription(wp.anomalyType(), wp.environment()) : wp.description(),
            wp.imagePath(),
            wp.audioPath(),
            wp.infraredPath(),
            co, ch4, o2, h2s, temp, hum, sound,
            Instant.now()
        );
    }

    private InspectionEvent buildSimulated() {
        String[] wp    = SIM_WAYPOINTS[rng.nextInt(SIM_WAYPOINTS.length)];
        String[] entry = SIM_DESCRIPTIONS[rng.nextInt(SIM_DESCRIPTIONS.length)];

        AnomalyType anomaly = AnomalyType.valueOf(entry[0]);
        Environment env     = Environment.values()[rng.nextInt(Environment.values().length)];
        SafetyGrade grade   = gradeFor(anomaly);

        String waypointId = wp[0] + "_" + System.currentTimeMillis() + "_" + wp[1];
        String description = String.format(entry[1], env.label.toLowerCase());

        // Simulated multi-gas sensor readings correlated with anomaly severity
        boolean danger = grade == SafetyGrade.GRADE_1;
        double co    = danger ? 5  + rng.nextDouble() * 20  : rng.nextDouble() * 3;     // PPM
        double ch4   = danger ? 0.2 + rng.nextDouble() * 0.5 : rng.nextDouble() * 0.05; // %VOL
        double o2    = danger ? 18  + rng.nextDouble() * 2  : 20 + rng.nextDouble() * 1; // %VOL
        double h2s   = danger ? 1   + rng.nextDouble() * 4  : rng.nextDouble() * 0.5;   // PPM
        double temp  = 18  + rng.nextDouble() * 45;
        double hum   = 30  + rng.nextDouble() * 50;
        double sound = 55  + rng.nextDouble() * 40;   // 55–95 dB industrial ambient

        return new InspectionEvent(
            waypointId, formatMac(wp[0]), wp[2],
            env, anomaly, grade, description,
            null,  // no real image in simulated mode
            null,  // no real audio in simulated mode
            null,  // no infrared video in simulated mode
            co, ch4, o2, h2s, temp, hum, sound,
            Instant.now()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private AnomalyType deriveAnomalyType(String dirName, String description, boolean isAnomaly) {
        if (!isAnomaly) return AnomalyType.NORMAL;
        String lower = (dirName + " " + description).toLowerCase();
        if (lower.contains("fall"))         return AnomalyType.FALL_DETECTED;
        if (lower.contains("fire"))         return AnomalyType.FIRE_DETECTED;
        if (lower.contains("cigarette") || lower.contains("smok")) return AnomalyType.SMOKING;
        if (lower.contains("nonautomobile") || lower.contains("vehicle")) return AnomalyType.UNAUTHORIZED_VEHICLE;
        if (lower.contains("head") || lower.contains("helmet"))  return AnomalyType.NO_HELMET;
        if (lower.contains("glove"))        return AnomalyType.NO_GLOVES;
        if (lower.contains("mask"))         return AnomalyType.NO_MASK;
        return AnomalyType.NO_HELMET; // default anomaly
    }

    private Environment deriveEnvironment(String description, String dirName) {
        String lower = (description + " " + dirName).toLowerCase();
        if (lower.contains("tunnel"))       return Environment.TUNNEL;
        if (lower.contains("power") || lower.contains("generator") || lower.contains("kv"))
                                            return Environment.POWER_FACILITY;
        if (lower.contains("sintering"))    return Environment.SINTERING_EQUIPMENT;
        if (lower.contains("oil") || lower.contains("gas") || lower.contains("chemical"))
                                            return Environment.OIL_GAS_PLANT;
        if (lower.contains("coal") || lower.contains("conveyor"))
                                            return Environment.COAL_CONVEYOR;
        return Environment.values()[rng.nextInt(Environment.values().length)];
    }

    private SafetyGrade gradeFor(AnomalyType type) {
        return switch (type) {
            case FALL_DETECTED, FIRE_DETECTED, NO_HELMET -> SafetyGrade.GRADE_1;
            case NO_MASK, NO_GLOVES, SMOKING             -> SafetyGrade.GRADE_2;
            case UNAUTHORIZED_VEHICLE                    -> SafetyGrade.GRADE_3;
            case NORMAL                                  -> SafetyGrade.NORMAL;
        };
    }

    private String defaultDescription(AnomalyType type, Environment env) {
        return String.format("In the %s scenario, %s. Safety grade: %s.",
            env.label.toLowerCase(), type.label.toLowerCase(), gradeFor(type).label);
    }

    /**
     * Find sensor JSON file for a waypoint.
     * Checks wpDir first (for our Python-downloaded layout), then the standard
     * InspecSafe Other_modalities/<waypointId>/ location.
     */
    private File findSensorFile(File wpDir, String waypointId) {
        // 1. Fastest: sensor file sits alongside the image in the waypoint dir
        if (wpDir != null && wpDir.exists()) {
            File[] local = wpDir.listFiles(f ->
                f.getName().contains("_sensor_") && f.getName().endsWith(".txt"));
            if (local != null && local.length > 0) return local[0];
        }

        // 2. Standard InspecSafe layout: Other_modalities/<waypointId>/*_sensor_*.txt
        return findInOtherModalities(waypointId, "_sensor_", ".txt");
    }

    /** Find the real WAV audio file for a waypoint in Other_modalities. */
    private File findAudioFile(String waypointId) {
        return findInOtherModalities(waypointId, "_audio_", ".wav");
    }

    /** Find the infrared MP4 for a waypoint — the thermal-camera equivalent for InspecSafe. */
    private File findInfraredFile(String waypointId) {
        return findInOtherModalities(waypointId, "_infrared_", ".mp4");
    }

    /** Generic helper — searches Other_modalities/<waypointId>/ across all splits. */
    private File findInOtherModalities(String waypointId, String nameContains, String extension) {
        for (String split : new String[]{"", "train", "test", "val"}) {
            String base = split.isEmpty()
                ? "Other_modalities/" + waypointId
                : split + "/Other_modalities/" + waypointId;
            File otherDir = new File(datasetRoot, base);
            if (otherDir.exists() && otherDir.isDirectory()) {
                File[] files = otherDir.listFiles(f ->
                    f.getName().contains(nameContains) && f.getName().endsWith(extension));
                if (files != null && files.length > 0) return files[0];
            }
        }
        return null;
    }

    /**
     * Parse InspecSafe sensor file.
     *
     * Real InspecSafe sensor files are JSON objects:
     * { "env": [ {"symbol":"temperature","showValue":25.7},
     *            {"symbol":"humidity",   "showValue":23.4},
     *            {"symbol":"CO",         "showValue":6.0, "unit":"PPM"},
     *            {"symbol":"CH4",        "showValue":0.0, "unit":"%VOL"},
     *            {"symbol":"O2",         "showValue":20.4,"unit":"%VOL"},
     *            {"symbol":"H2S",        "showValue":0.0, "unit":"PPM"} ] }
     *
     * Legacy files from our Python downloader use "key: value" text.
     *
     * Returns double[7]:
     *   [0] CO PPM  [1] CH4 %VOL  [2] O2 %VOL  [3] H2S PPM
     *   [4] temp °C [5] humidity %RH [6] NaN (sound from WAV)
     * Any missing field is Double.NaN.
     */
    private double[] parseSensorFile(File f) {
        try {
            String content = Files.readString(f.toPath()).trim();
            double co = Double.NaN, ch4 = Double.NaN, o2 = Double.NaN, h2s = Double.NaN;
            double temp = Double.NaN, hum = Double.NaN;

            if (content.startsWith("{") || content.startsWith("[")) {
                // Real InspecSafe JSON sensor format
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                if (root.has("env") && root.get("env").isJsonArray()) {
                    for (com.google.gson.JsonElement el : root.getAsJsonArray("env")) {
                        JsonObject item = el.getAsJsonObject();
                        if (!item.has("symbol") || !item.has("showValue")) continue;
                        String sym = item.get("symbol").getAsString().toLowerCase();
                        double val = item.get("showValue").getAsDouble();
                        switch (sym) {
                            case "temperature" -> temp = val;
                            case "humidity"    -> hum  = val;
                            case "co"          -> co   = val;   // PPM — carbon monoxide
                            case "ch4"         -> ch4  = val;   // %VOL — methane
                            case "o2"          -> o2   = val;   // %VOL — oxygen
                            case "h2s"         -> h2s  = val;   // PPM — hydrogen sulphide
                        }
                    }
                }
            } else {
                // Legacy key:value format (from our Python downloader)
                for (String line : content.split("\n")) {
                    String low = line.toLowerCase();
                    if      (low.contains("co"))    co   = parseValue(line);
                    else if (low.contains("ch4"))   ch4  = parseValue(line);
                    else if (low.contains("o2"))    o2   = parseValue(line);
                    else if (low.contains("h2s"))   h2s  = parseValue(line);
                    else if (low.contains("temp"))  temp = parseValue(line);
                    else if (low.contains("humid")) hum  = parseValue(line);
                }
            }

            // sound comes from the WAV file, not the sensor txt → always NaN
            return new double[]{co, ch4, o2, h2s, temp, hum, Double.NaN};
        } catch (Exception e) { return null; }
    }

    private double parseValue(String line) {
        try {
            String[] parts = line.split("[:\\s]+");
            for (String p : parts) {
                try { return Double.parseDouble(p.trim()); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String formatMac(String digits) {
        if (digits.length() < 12) return "ROBOT-" + digits;
        // Format as XX:XX:XX:XX:XX:XX style
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(12, digits.length()); i += 2) {
            if (sb.length() > 0) sb.append(":");
            sb.append(digits, i, Math.min(i + 2, digits.length()));
        }
        return sb.toString();
    }

    private void buildSimulatedEntries() {
        // No-op: simulated events are built on-demand in buildSimulated()
        usingRealData = false;
    }
}
