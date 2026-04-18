package edu.neu.safety.replay;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import edu.neu.safety.model.SensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.time.Duration;
import java.util.*;

/**
 * Replays the public MQ-sensor CSV dataset as an Akka Streams Source of
 * {@link SensorReading}s. The real system would take a live serial feed
 * from an Arduino running the MQ array — this class substitutes a CSV
 * replay so the demo is reproducible and we don't need the hardware at hand.
 *
 * Each row of the CSV becomes one {@code SensorReading} emitted at a
 * configurable tick interval, so the same Akka graph that processes live
 * data also processes replay data unchanged.
 */
public class CsvReplaySource {

    private static final Logger log = LoggerFactory.getLogger(CsvReplaySource.class);

    /**
     * Build a throttled Source that emits one SensorReading per tick.
     *
     * @param csvPath        path to the dataset CSV (see data/Gas_Sensors_Measurements.csv)
     * @param tickInterval   spacing between emitted readings; controls replay speed
     * @return a Source that iterates the CSV once and then completes
     */
    public static Source<SensorReading, NotUsed> create(String csvPath, Duration tickInterval) {
        return Source.fromIterator(() -> new CsvSensorIterator(csvPath))
            .throttle(1, tickInterval);
    }

    /**
     * Pull-based iterator over CSV rows. Kept package-private since the
     * Source factory above is the only legitimate way to construct one —
     * callers should not touch OpenCSV state directly.
     */
    private static class CsvSensorIterator implements Iterator<SensorReading> {
        private final CSVReader reader;
        private final String[] headers;
        private String[] nextRow;

        /** Open the file, eagerly read headers and the first row so hasNext() is honest. */
        CsvSensorIterator(String csvPath) {
            try {
                reader = new CSVReaderBuilder(new FileReader(csvPath)).build();
                headers = reader.readNext();
                if (headers != null) {
                    for (int i = 0; i < headers.length; i++) {
                        headers[i] = headers[i].trim();
                    }
                }
                nextRow = reader.readNext();
                log.info("CSV replay initialized: {} columns, headers={}",
                    headers != null ? headers.length : 0, Arrays.toString(headers));
            } catch (Exception e) {
                throw new RuntimeException("Failed to open CSV: " + csvPath, e);
            }
        }

        @Override
        public boolean hasNext() {
            return nextRow != null;
        }

        @Override
        public SensorReading next() {
            if (nextRow == null) throw new NoSuchElementException();

            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.length && i < nextRow.length; i++) {
                row.put(headers[i], nextRow[i].trim());
            }

            try {
                nextRow = reader.readNext();
            } catch (Exception e) {
                nextRow = null;
            }

            return parseReading(row);
        }

        /**
         * Convert a raw CSV row (string → string map) into a typed
         * SensorReading. Any non-numeric cell is defaulted to 0.0 instead of
         * throwing because the dataset has occasional blanks and we don't
         * want a single bad row to crash replay.
         */
        private SensorReading parseReading(Map<String, String> row) {
            Map<String, Double> sensorValues = new LinkedHashMap<>();
            for (String sensorType : SensorReading.SENSOR_TYPES) {
                String value = row.getOrDefault(sensorType,
                    row.getOrDefault(sensorType.toLowerCase(), "0"));
                try {
                    sensorValues.put(sensorType, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    sensorValues.put(sensorType, 0.0);
                }
            }
            String label = row.getOrDefault("label", row.getOrDefault("Label", "No Gas"));
            return new SensorReading(sensorValues, label, System.currentTimeMillis());
        }
    }
}
