package com.safety.stream;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.stream.javadsl.Source;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.safety.agents.FusionAgent;
import com.safety.agents.SensorAgent;
import com.safety.agents.ThermalAgent;
import com.safety.http.SafetyHttpServer;
import com.safety.protocol.ThermalProtocol;

import java.io.FileReader;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class DataReplayStream {

    private final ActorSystem<?> system;
    private final ClusterSharding sharding;
    private final String csvPath;
    private final long replayIntervalMs;
    private final ActorRef<FusionAgent.Command> fusionAgent;
    private final ActorRef<ThermalAgent.Command> thermalAgent;
    private final SafetyHttpServer httpServer;

    private static final String THERMAL_BASE_PATH = "data/Thermal Camera Images";
    private static final String[] SENSOR_TYPES = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};
    private static final int[] SENSOR_COLUMNS = {1, 2, 3, 4, 5, 6, 7};

    /** label → sorted list of absolute paths to thermal PNGs */
    private final Map<String, List<String>> thermalIndex = new HashMap<>();

    public DataReplayStream(ActorSystem<?> system, ClusterSharding sharding,
                            String csvPath, long replayIntervalMs,
                            ActorRef<FusionAgent.Command> fusionAgent,
                            ActorRef<ThermalAgent.Command> thermalAgent,
                            SafetyHttpServer httpServer) {
        this.system = system;
        this.sharding = sharding;
        this.csvPath = csvPath;
        this.replayIntervalMs = replayIntervalMs;
        this.fusionAgent = fusionAgent;
        this.thermalAgent = thermalAgent;
        this.httpServer = httpServer;
        loadThermalIndex();
    }

    // Holds one row of parsed sensor data + label
    public record SensorRow(
        int[] sensorValues,
        String gasLabel,
        Instant timestamp
    ) {}

    /** Scan all subdirs under thermalBasePath and build label → sorted file list. */
    private void loadThermalIndex() {
        File base = new File(THERMAL_BASE_PATH);
        if (!base.exists() || !base.isDirectory()) {
            system.log().warn("Thermal base path not found: {}", THERMAL_BASE_PATH);
            return;
        }
        File[] subdirs = base.listFiles(File::isDirectory);
        if (subdirs == null) return;

        for (File dir : subdirs) {
            String label = dir.getName(); // e.g. "Mixture"
            File[] pngs = dir.listFiles(f -> f.isFile() &&
                (f.getName().toLowerCase().endsWith(".png") ||
                 f.getName().toLowerCase().endsWith(".jpg") ||
                 f.getName().toLowerCase().endsWith(".jpeg")));
            if (pngs == null || pngs.length == 0) continue;

            List<String> paths = new ArrayList<>();
            for (File png : pngs) {
                paths.add(png.getAbsolutePath());
            }
            // Sort by numeric prefix in filename (e.g. "0_Mixture.png" < "100_Mixture.png")
            paths.sort((a, b) -> {
                int na = extractNumericPrefix(new File(a).getName());
                int nb = extractNumericPrefix(new File(b).getName());
                return Integer.compare(na, nb);
            });
            thermalIndex.put(label, paths);
            system.log().info("Thermal index: label={} files={}", label, paths.size());
        }
        system.log().info("Thermal index loaded: {} labels", thermalIndex.size());
    }

    private static int extractNumericPrefix(String filename) {
        int idx = filename.indexOf('_');
        if (idx <= 0) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(filename.substring(0, idx));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // Load all rows from CSV into memory, interleaved by class for demo variety.
    // The CSV is sorted NoGas→Perfume→Smoke→Mixture, so without interleaving the
    // first hazard wouldn't appear until row ~3200 (10+ minutes at 200ms/row).
    public List<SensorRow> loadCsv() {
        Map<String, List<SensorRow>> byClass = new LinkedHashMap<>();
        try (CSVReader reader = new CSVReaderBuilder(new FileReader(csvPath))
                .withSkipLines(1)
                .build()) {
            String[] line;
            int rowNum = 0;
            while ((line = reader.readNext()) != null) {
                int[] values = new int[7];
                for (int i = 0; i < 7; i++) {
                    values[i] = Integer.parseInt(line[SENSOR_COLUMNS[i]].trim());
                }
                String label = line[8].trim();
                Instant ts = Instant.now().plusMillis(rowNum * 2000L);
                byClass.computeIfAbsent(label, k -> new ArrayList<>())
                       .add(new SensorRow(values, label, ts));
                rowNum++;
            }
        } catch (Exception e) {
            system.log().error("Failed to load CSV: {}", e.getMessage());
            return Collections.emptyList();
        }

        // Round-robin interleave: one row from each class in turn.
        // Result: NoGas, Perfume, Smoke, Mixture, NoGas, Perfume, Smoke, Mixture, …
        // Every 4 rows covers all gas classes so hazard events appear immediately.
        List<SensorRow> interleaved = new ArrayList<>();
        List<List<SensorRow>> buckets = new ArrayList<>(byClass.values());
        int maxLen = buckets.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxLen; i++) {
            for (List<SensorRow> bucket : buckets) {
                if (i < bucket.size()) interleaved.add(bucket.get(i));
            }
        }

        system.log().info("Loaded {} rows, interleaved across {} classes: {}",
            interleaved.size(), byClass.size(), byClass.keySet());
        return interleaved;
    }

    // Start replaying data as an Akka Stream
    public void startReplay() {
        List<SensorRow> rows = loadCsv();
        if (rows.isEmpty()) {
            system.log().error("No data to replay!");
            return;
        }

        system.log().info("Starting data replay: {} rows at {}ms intervals",
            rows.size(), replayIntervalMs);

        final int[] rowCounter = {0};

        Source.from(rows)
            .throttle(1, Duration.ofMillis(replayIntervalMs))
            .runForeach(row -> {
                Instant now = Instant.now();
                int rowNum = rowCounter[0]++;

                // --- Sensor shards ---
                for (int i = 0; i < SENSOR_TYPES.length; i++) {
                    String sensorType = SENSOR_TYPES[i];
                    int value = row.sensorValues()[i];

                    EntityRef<SensorAgent.Command> sensorRef =
                        sharding.entityRefFor(SensorAgent.ENTITY_KEY, sensorType);

                    sensorRef.tell(new SensorAgent.SetFusionRef(fusionAgent));
                    sensorRef.tell(new SensorAgent.ProcessReading(sensorType, value, now));

                    // Update fault-tolerance dashboard with live PPM immediately
                    // (FusionAgent batches for 5s so we push directly here too)
                    if (httpServer != null) {
                        httpServer.updateLivePpm(sensorType, value);
                    }
                }

                // --- Thermal image for this row ---
                String label = row.gasLabel();
                List<String> images = thermalIndex.get(label);
                boolean hasImage = images != null && !images.isEmpty();

                if (hasImage) {
                    String absPath = images.get(rowNum % images.size());
                    if (thermalAgent != null) {
                        thermalAgent.tell(new ThermalAgent.ProcessFrame(
                            new ThermalProtocol.ThermalFrame(absPath, now)
                        ));
                    }
                    if (httpServer != null) {
                        String relPath = label + "/" + new File(absPath).getName();
                        httpServer.pushThermalFrame(relPath, label);
                    }
                } else {
                    // No thermal image folder for this gas class — tell the dashboard explicitly
                    if (httpServer != null) {
                        httpServer.pushThermalFrame(null, label);
                    }
                }
            }, system)
            .whenComplete((done, err) -> {
                if (err != null) {
                    system.log().error("Replay stream failed: {}", err.getMessage());
                } else {
                    system.log().info("Replay stream completed — all {} rows processed", rows.size());
                }
            });
    }
}
