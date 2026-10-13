package dev.mcattle.client;

import dev.mcattle.core.stt.Wav;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/** Captures 16 kHz mono 16-bit microphone audio on a background thread while push-to-talk is held. */
public final class MicRecorder {
    private static final AudioFormat FORMAT = new AudioFormat(Wav.SAMPLE_RATE, 16, 1, true, false);
    private static final int MAX_BYTES = Wav.SAMPLE_RATE * 2 * 30;

    private volatile boolean recording;
    private volatile double level;
    private TargetDataLine line;
    private Thread thread;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /** Starts recording; throws if no microphone is available. */
    public synchronized void start() throws LineUnavailableException {
        if (recording) return;
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) throw new LineUnavailableException("no microphone supporting 16 kHz mono");
        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(FORMAT);
        line.start();
        buffer.reset();
        recording = true;
        thread = new Thread(this::loop, "mcattle-mic");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        byte[] chunk = new byte[1600]; // 50 ms
        while (recording) {
            int n = line.read(chunk, 0, chunk.length);
            if (n <= 0) continue;
            level = Wav.rms(chunk, n);
            synchronized (buffer) {
                if (buffer.size() + n <= MAX_BYTES) buffer.write(chunk, 0, n);
            }
        }
    }

    /** Stops recording and returns the captured PCM. */
    public synchronized byte[] stop() {
        if (!recording) return new byte[0];
        recording = false;
        try {
            if (thread != null) thread.join(300);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        line.stop();
        line.close();
        level = 0;
        synchronized (buffer) {
            return buffer.toByteArray();
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /** Current input level 0..1 for the HUD meter. */
    public double level() {
        return level;
    }

    public double seconds() {
        synchronized (buffer) {
            return buffer.size() / (double) (Wav.SAMPLE_RATE * 2);
        }
    }
}
