package dev.mcattle.core.stt;

import java.io.ByteArrayOutputStream;

/** Wraps raw 16-bit little-endian mono PCM in a WAV container. */
public final class Wav {
    public static final int SAMPLE_RATE = 16_000;

    private Wav() {
    }

    public static byte[] fromPcm16Mono(byte[] pcm, int sampleRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = sampleRate * 2;
        writeAscii(out, "RIFF");
        writeInt(out, 36 + pcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);          // fmt chunk size
        writeShort(out, 1);         // PCM
        writeShort(out, 1);         // mono
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, 2);         // block align
        writeShort(out, 16);        // bits per sample
        writeAscii(out, "data");
        writeInt(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    /** Root-mean-square level of 16-bit PCM, 0..1. */
    public static double rms(byte[] pcm, int length) {
        long sum = 0;
        int samples = length / 2;
        if (samples == 0) return 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / samples) / 32768.0;
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) out.write(s.charAt(i));
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
