package dev.mcattle.core.stt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transcribes push-to-talk audio through an OpenAI-compatible {@code /v1/audio/transcriptions} endpoint
 * (whisper.cpp server, faster-whisper-server / speaches, LocalAI, Groq, OpenAI...).
 */
public final class SpeechToText {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String language;

    public SpeechToText(String endpoint, String apiKey, String model, String language) {
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "whisper-1" : model;
        this.language = language == null ? "" : language;
    }

    /** Words that commonly appear in Minecraft commands; passed as a prompt to bias the recognizer. */
    private static final String VOCABULARY_HINT =
            "Minecraft. Give me wood, logs, planks, cobblestone, diamonds, iron, pickaxe, crafting table, furnace. "
                    + "Build a house. Follow me. Attack me. Stop.";

    public CompletableFuture<String> transcribe(byte[] wav, String extraNames) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcribeBlocking(wav, extraNames);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public String transcribeBlocking(byte[] wav, String extraNames) throws IOException, InterruptedException {
        String boundary = "----mcattle" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
        field(body, boundary, "model", model);
        field(body, boundary, "response_format", "json");
        if (!language.isBlank()) field(body, boundary, "language", language);
        field(body, boundary, "prompt", VOCABULARY_HINT + (extraNames == null || extraNames.isBlank() ? "" : " Names: " + extraNames + "."));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("transcription HTTP " + response.statusCode() + ": " + response.body());
        }
        return parseText(response.body());
    }

    static String parseText(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
            JsonElement text = json.get("text");
            return text == null || text.isJsonNull() ? "" : text.getAsString().trim();
        }
        return trimmed; // plain-text response formats
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
