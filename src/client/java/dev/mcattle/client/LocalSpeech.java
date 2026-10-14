package dev.mcattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline speech recognition on the player's own computer with Vosk. Nothing has to be installed or hosted:
 * the first time it's used, the Vosk engine (from Maven Central, checksum-verified) and a small speech model
 * are downloaded into {@code config/mcattle/speech}. Vosk is loaded in its own class loader (it ships native
 * code for Windows, macOS and Linux) so the mod jar stays small.
 */
final class LocalSpeech {
    private static final String VOSK_VERSION = "0.3.45";
    private static final String VOSK_URL = "https://repo1.maven.org/maven2/com/alphacephei/vosk/" + VOSK_VERSION + "/vosk-" + VOSK_VERSION + ".jar";
    private static final String VOSK_SHA1 = "f6e44146c02e03e662718f71ff22e4a49642bdda";

    private final Path dir;
    private final String modelUrl;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile Object model;
    private Constructor<?> recognizerCtor;
    private Method acceptWaveForm, finalResult;

    LocalSpeech(Path dir, String modelUrl) {
        this.dir = dir;
        this.modelUrl = modelUrl;
    }

    /** Turns 16 kHz mono 16-bit PCM into text. Blocks (downloads on first use); status gets progress messages. */
    synchronized String transcribe(byte[] pcm, Consumer<String> status) throws Exception {
        load(status);
        AutoCloseable recognizer = (AutoCloseable) recognizerCtor.newInstance(model, 16000f);
        try {
            acceptWaveForm.invoke(recognizer, pcm, pcm.length);
            String json = (String) finalResult.invoke(recognizer);
            JsonElement text = JsonParser.parseString(json).getAsJsonObject().get("text");
            return text == null || text.isJsonNull() ? "" : text.getAsString().trim();
        } finally {
            recognizer.close();
        }
    }

    /** Downloads and loads everything ahead of time (called when the push-to-talk key goes down). */
    synchronized void prepare(Consumer<String> status) {
        try {
            load(status);
        } catch (Exception ignored) {
            // reported properly by transcribe()
        }
    }

    /** Not synchronized: called from the game thread while a download may hold the lock. */
    boolean isLoaded() {
        return model != null;
    }

    private void load(Consumer<String> status) throws Exception {
        if (model != null) return;
        Files.createDirectories(dir);
        Path jar = dir.resolve("vosk-" + VOSK_VERSION + ".jar");
        if (!Files.exists(jar)) {
            download(VOSK_URL, jar, "speech engine", status);
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar)));
            if (!sha1.equals(VOSK_SHA1)) {
                Files.deleteIfExists(jar);
                throw new IOException("speech engine download was corrupted, try again");
            }
        }
        Path modelDir = findModel();
        if (modelDir == null) {
            Path zip = dir.resolve("model.zip.part");
            download(modelUrl, zip, "speech model", status);
            status.accept("unpacking speech model...");
            unzip(zip, dir.resolve("models"));
            Files.deleteIfExists(zip);
            modelDir = findModel();
            if (modelDir == null) throw new IOException("the speech model archive didn't contain a Vosk model");
        }
        status.accept("loading speech model...");
        ClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, LocalSpeech.class.getClassLoader());
        Class<?> libVosk = Class.forName("org.vosk.LibVosk", true, loader);
        Class<?> logLevel = Class.forName("org.vosk.LogLevel", true, loader);
        libVosk.getMethod("setLogLevel", logLevel).invoke(null, logLevel.getField("WARNINGS").get(null));
        Class<?> modelClass = Class.forName("org.vosk.Model", true, loader);
        Class<?> recognizerClass = Class.forName("org.vosk.Recognizer", true, loader);
        model = modelClass.getConstructor(String.class).newInstance(modelDir.toAbsolutePath().toString());
        recognizerCtor = recognizerClass.getConstructor(modelClass, float.class);
        acceptWaveForm = recognizerClass.getMethod("acceptWaveForm", byte[].class, int.class);
        finalResult = recognizerClass.getMethod("getFinalResult");
    }

    /** A folder under models/ that looks like a Vosk model (has an "am" or "conf" folder). */
    private Path findModel() throws IOException {
        Path models = dir.resolve("models");
        if (!Files.isDirectory(models)) return null;
        try (Stream<Path> list = Files.list(models)) {
            return list.filter(p -> Files.isDirectory(p.resolve("am")) || Files.isDirectory(p.resolve("conf")))
                    .filter(p -> Files.exists(p.resolve(".complete")))
                    .findFirst().orElse(null);
        }
    }

    private void download(String url, Path target, String what, Consumer<String> status) throws Exception {
        status.accept("downloading " + what + " (one time)...");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new IOException("couldn't download " + what + ": HTTP " + response.statusCode());
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 5 != lastPct / 5) {
                        lastPct = pct;
                        status.accept("downloading " + what + " (one time)... " + pct + "%");
                    }
                }
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void unzip(Path zip, Path into) throws IOException {
        Files.createDirectories(into);
        Path root = into.toAbsolutePath().normalize();
        Path top = null;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("bad entry in model archive: " + e.getName());
                if (top == null && root.relativize(out).getNameCount() > 0) top = root.resolve(root.relativize(out).getName(0));
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (top != null && Files.isDirectory(top)) Files.writeString(top.resolve(".complete"), "ok");
    }
}
