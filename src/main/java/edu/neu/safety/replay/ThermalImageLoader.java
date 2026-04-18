package edu.neu.safety.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Stands in for a real thermal camera. A proper FLIR/Lepton feed would
 * return a 2-D temperature matrix that we'd aggregate — for the demo we
 * either take a grayscale image off disk and map pixel intensity onto a
 * 20–100°C range, or synthesize features if no image is available.
 *
 * The point of this class is not to produce physically accurate temperatures
 * but to produce a believable stream of (max, avg, anomaly) triples that the
 * FusionAgent can correlate with the gas sensor stream.
 */
public class ThermalImageLoader {

    private static final Logger log = LoggerFactory.getLogger(ThermalImageLoader.class);

    /**
     * Summary statistics the FusionAgent actually cares about.
     *
     * @param maxTemp hottest pixel temperature (°C)
     * @param avgTemp mean pixel temperature (°C)
     * @param anomaly true if maxTemp exceeds our anomaly threshold
     */
    public record ThermalFeatures(double maxTemp, double avgTemp, boolean anomaly) {}

    /**
     * Load a grayscale image off disk and produce thermal stats from it.
     * If the file is missing or unreadable we fall back to synthetic
     * features so the pipeline keeps running during development on
     * machines that don't have the dataset.
     *
     * @param imagePath path to a PNG/JPG grayscale frame
     */
    public static ThermalFeatures extractFeatures(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) return generateSyntheticFeatures();

            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return generateSyntheticFeatures();

            int width = image.getWidth();
            int height = image.getHeight();
            double totalIntensity = 0;
            double maxIntensity = 0;
            int pixelCount = width * height;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    // Average the three channels — works fine for the grayscale
                    // frames in our dataset, and is graceful if someone drops
                    // in a colored debug image by mistake.
                    double intensity = (r + g + b) / 3.0;
                    totalIntensity += intensity;
                    maxIntensity = Math.max(maxIntensity, intensity);
                }
            }

            double avgIntensity = totalIntensity / pixelCount;
            // Linear map: 0 → 20°C (room temp), 255 → 100°C (hot surface).
            double maxTemp = 20.0 + (maxIntensity / 255.0) * 80.0;
            double avgTemp = 20.0 + (avgIntensity / 255.0) * 80.0;
            boolean anomaly = maxTemp > 60.0;
            return new ThermalFeatures(maxTemp, avgTemp, anomaly);
        } catch (Exception e) {
            log.warn("Failed to load thermal image {}: {}", imagePath, e.getMessage());
            return generateSyntheticFeatures();
        }
    }

    /**
     * Generate plausible random thermal stats. Used as a fallback when the
     * image can't be read, and also during unit-test style runs when we
     * want the pipeline to tick without any disk I/O.
     */
    public static ThermalFeatures generateSyntheticFeatures() {
        double baseTemp = 25.0 + Math.random() * 10.0;
        double maxTemp = baseTemp + Math.random() * 15.0;
        boolean anomaly = maxTemp > 45.0;
        return new ThermalFeatures(maxTemp, baseTemp, anomaly);
    }
}
