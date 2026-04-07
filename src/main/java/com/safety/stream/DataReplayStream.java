package com.safety.stream;

import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.stream.javadsl.Source;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.safety.agents.SensorAgent;
import com.safety.agents.FusionAgent;

import java.io.FileReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DataReplayStream {

    private final ActorSystem<?> system;
    private final ClusterSharding sharding;
    private final String csvPath;
    private final long replayIntervalMs;
    private final akka.actor.typed.ActorRef<FusionAgent.Command> fusionAgent;

    private static final String[] SENSOR_TYPES = {"MQ2", "MQ3", "MQ5", "MQ6", "MQ7", "MQ8", "MQ135"};
    private static final int[] SENSOR_COLUMNS = {1, 2, 3, 4, 5, 6, 7};

    public DataReplayStream(ActorSystem<?> system, ClusterSharding sharding,
                            String csvPath, long replayIntervalMs,
                            akka.actor.typed.ActorRef<FusionAgent.Command> fusionAgent) {
        this.system = system;
        this.sharding = sharding;
        this.csvPath = csvPath;
        this.replayIntervalMs = replayIntervalMs;
        this.fusionAgent = fusionAgent;
    }

    // Holds one row of parsed sensor data + label
    public record SensorRow(
        int[] sensorValues,
        String gasLabel,
        Instant timestamp
    ) {}

    // Load all rows from CSV into memory
    public List<SensorRow> loadCsv() {
        List<SensorRow> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReaderBuilder(new FileReader(csvPath))
                .withSkipLines(1) // skip header
                .build()) {
            String[] line;
            int rowNum = 0;
            while ((line = reader.readNext()) != null) {
                int[] values = new int[7];
                for (int i = 0; i < 7; i++) {
                    values[i] = Integer.parseInt(line[SENSOR_COLUMNS[i]].trim());
                }
                String label = line[8].trim(); // Gas column
                // Simulate 2-second intervals from a base timestamp
                Instant ts = Instant.now().plusMillis(rowNum * 2000L);
                rows.add(new SensorRow(values, label, ts));
                rowNum++;
            }
            system.log().info("Loaded {} rows from CSV", rows.size());
        } catch (Exception e) {
            system.log().error("Failed to load CSV: {}", e.getMessage());
        }
        return rows;
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

        // First, tell each sensor shard where the fusion agent is
        for (String sensorType : SENSOR_TYPES) {
            EntityRef<SensorAgent.Command> sensorRef =
                sharding.entityRefFor(SensorAgent.ENTITY_KEY, sensorType);
            sensorRef.tell(new SensorAgent.SetFusionRef(fusionAgent));
        }

        // Create a ticking source that emits one row at a time
        Source.from(rows)
            .throttle(1, Duration.ofMillis(replayIntervalMs))
            .runForeach(row -> {
                Instant now = Instant.now();

                // Send each sensor value to its sharded SensorAgent
                for (int i = 0; i < SENSOR_TYPES.length; i++) {
                    String sensorType = SENSOR_TYPES[i];
                    int value = row.sensorValues()[i];

                    EntityRef<SensorAgent.Command> sensorRef =
                        sharding.entityRefFor(SensorAgent.ENTITY_KEY, sensorType);

                    sensorRef.tell(new SensorAgent.ProcessReading(
                        sensorType, value, now
                    ));
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