package com.safety.agents;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.safety.protocol.AudioProtocol;

import javax.sound.sampled.*;
import java.io.File;
import java.time.Instant;

/**
 * AudioAgent
 */
public class AudioAgent extends AbstractBehavior<AudioAgent.Command> {

    public interface Command {}

    public record SetFusionRef(ActorRef<FusionAgent.Command> fusionAgent) implements Command {}

    public record ProcessAudio(AudioProtocol.AudioFrame frame) implements Command {}

    /** Simulate an audio event when no real .wav is available */
    public record SimulateAudio(double contextEnergy, boolean contextAnomaly) implements Command {}

    // Anomaly thresholds (tunable via config in future)
    private static final double ENERGY_ANOMALY_THRESHOLD = 0.65;
    private static final double FREQ_ANOMALY_MIN_HZ      = 2000.0;
    private static final int    SAMPLE_WINDOW_BYTES      = 8192;

    private ActorRef<FusionAgent.Command> fusionAgent;
    private int framesProcessed = 0;

    public static Behavior<Command> create() {
        return Behaviors.setup(AudioAgent::new);
    }

    private AudioAgent(ActorContext<Command> context) {
        super(context);
        context.getLog().info("AudioAgent started (InspecSafe-V1 audio modality)");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SetFusionRef.class,  this::onSetFusionRef)
            .onMessage(ProcessAudio.class,  this::onProcessAudio)
            .onMessage(SimulateAudio.class, this::onSimulateAudio)
            .build();
    }

    private Behavior<Command> onSetFusionRef(SetFusionRef msg) {
        this.fusionAgent = msg.fusionAgent();
        getContext().getLog().info("AudioAgent fusion ref set");
        return this;
    }

    // WAV processing
    private Behavior<Command> onProcessAudio(ProcessAudio msg) {
        if (fusionAgent == null) return this;

        File wav = new File(msg.frame().filePath());
        if (!wav.exists()) {
            getContext().getLog().debug("AudioAgent: file not found {}, simulating", wav.getPath());
            fusionAgent.tell(new FusionAgent.AudioUpdate(simulateFromFilename(wav.getName(), msg.frame().timestamp())));
            return this;
        }

        AudioProtocol.AudioEvent event = analyzeWav(wav, msg.frame().timestamp());
        framesProcessed++;

        getContext().getLog().debug("AudioAgent: frame {} rms={} anomaly={} label={}",
            framesProcessed, String.format("%.3f", event.rmsEnergy()),
            event.anomalyDetected(), event.label());

        fusionAgent.tell(new FusionAgent.AudioUpdate(event));
        return this;
    }

    // ---- Simulation mode (no real file — derive from sensor context) ----
    private Behavior<Command> onSimulateAudio(SimulateAudio msg) {
        if (fusionAgent == null) return this;

        // Simulate a sound environment from the gas/thermal context
        double rms   = Math.min(1.0, msg.contextEnergy() + (Math.random() * 0.1 - 0.05));
        boolean anom = msg.contextAnomaly() && rms > 0.5;
        double freq  = anom ? 2500 + Math.random() * 1000 : 300 + Math.random() * 500;
        String label = anom ? "Alarm" : "Normal";
        String desc  = anom
            ? "Elevated acoustic energy consistent with alarm or mechanical stress"
            : "Normal ambient industrial sound levels";

        AudioProtocol.AudioEvent event = new AudioProtocol.AudioEvent(
            anom, rms, freq, label, desc, Instant.now()
        );
        fusionAgent.tell(new FusionAgent.AudioUpdate(event));
        return this;
    }

    // ===================================================================
    //  WAV ANALYSIS — energy + zero-crossing rate
    // ===================================================================

    private AudioProtocol.AudioEvent analyzeWav(File wav, Instant timestamp) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat format = ais.getFormat();
            int channels  = format.getChannels();
            int frameSize = format.getFrameSize();
            float sampleRate = format.getSampleRate();

            byte[] buffer = new byte[SAMPLE_WINDOW_BYTES];
            int bytesRead = ais.read(buffer);
            if (bytesRead <= 0) return quietEvent(timestamp);

            // --- RMS energy ---
            double sumSq = 0;
            int count = 0;
            boolean is16Bit = format.getSampleSizeInBits() == 16;

            if (is16Bit) {
                for (int i = 0; i + 1 < bytesRead; i += 2) {
                    short s = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    double norm = s / 32768.0;
                    sumSq += norm * norm;
                    count++;
                }
            } else {
                for (int i = 0; i < bytesRead; i++) {
                    double norm = (buffer[i] - 128) / 128.0;
                    sumSq += norm * norm;
                    count++;
                }
            }
            double rms = count > 0 ? Math.sqrt(sumSq / count) : 0.0;
            double rmsNorm = Math.min(1.0, rms * 5.0); // scale 0-0.2 → 0-1

            // --- Zero-crossing rate → approximate dominant frequency ---
            int zeroCrossings = 0;
            if (is16Bit) {
                boolean prevPositive = false;
                for (int i = 0; i + 1 < Math.min(bytesRead, 4096); i += 2) {
                    short s = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    boolean positive = s >= 0;
                    if (i > 0 && positive != prevPositive) zeroCrossings++;
                    prevPositive = positive;
                }
            }
            int samplesAnalyzed = Math.min(bytesRead / (is16Bit ? 2 : 1), 2048);
            double zcr = samplesAnalyzed > 0 ? (double) zeroCrossings / samplesAnalyzed : 0;
            double dominantHz = zcr * sampleRate / 2.0; // rough ZCR → freq estimate

            // --- Classify ---
            boolean energyAnomaly = rmsNorm > ENERGY_ANOMALY_THRESHOLD;
            boolean freqAnomaly   = dominantHz > FREQ_ANOMALY_MIN_HZ;
            boolean anomaly       = energyAnomaly || freqAnomaly;

            String label;
            String desc;
            if (energyAnomaly && freqAnomaly) {
                label = "Alarm";
                desc  = String.format("High-energy (%.2f) high-frequency (%.0fHz) audio — possible alarm or rupture", rmsNorm, dominantHz);
            } else if (energyAnomaly) {
                label = "Mechanical";
                desc  = String.format("Elevated acoustic energy (%.2f) — possible mechanical stress or impact", rmsNorm);
            } else if (freqAnomaly) {
                label = "Harmonic";
                desc  = String.format("Unusual dominant frequency %.0fHz — possible vibration or resonance", dominantHz);
            } else {
                label = "Normal";
                desc  = String.format("Normal ambient sound: rms=%.2f freq=%.0fHz", rmsNorm, dominantHz);
            }

            return new AudioProtocol.AudioEvent(anomaly, rmsNorm, dominantHz, label, desc, timestamp);

        } catch (Exception e) {
            // Infrastructure failure — propagate to supervisor for restart
            throw new RuntimeException("Audio analysis failed for " + wav.getPath(), e);
        }
    }

    private AudioProtocol.AudioEvent quietEvent(Instant ts) {
        return new AudioProtocol.AudioEvent(false, 0.0, 0.0, "Normal", "Silence or empty audio", ts);
    }

    private AudioProtocol.AudioEvent simulateFromFilename(String name, Instant ts) {
        // Derive rough simulation from filename (e.g. "anomaly_audio_..." vs "normal_...")
        boolean anomaly = name.toLowerCase().contains("anomaly") ||
                          name.toLowerCase().contains("alarm");
        double rms = anomaly ? 0.7 + Math.random() * 0.2 : 0.1 + Math.random() * 0.15;
        double hz  = anomaly ? 2200 + Math.random() * 800  : 350 + Math.random() * 200;
        return new AudioProtocol.AudioEvent(
            anomaly, rms, hz,
            anomaly ? "Alarm" : "Normal",
            "Simulated (file not found)", ts
        );
    }
}
