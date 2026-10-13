package dev.mcattle.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Local / self-hosted backend for any server speaking the OpenAI chat-completions protocol:
 * Ollama ({@code http://127.0.0.1:11434/v1}), LM Studio, llama.cpp server, vLLM, LocalAI...
 * Costs nothing per token, but small local models plan noticeably worse than Claude.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public LlmResult<Orders> plan(String situation) throws Exception {
        return call(SystemPrompt.PLANNER + "\nRespond with ONLY the JSON object, no markdown.", situation, 1500, Orders.class);
    }

    @Override
    public LlmResult<Banter> banter(String situation) throws Exception {
        String schema = "\nRespond with ONLY JSON like {\"lines\":[{\"npc\":\"Name\",\"text\":\"...\"}]}";
        return call(SystemPrompt.BANTER + schema, situation, 300, Banter.class);
    }

    @Override
    public LlmResult<Design> design(String context) throws Exception {
        return call(SystemPrompt.DESIGNER + "\nRespond with ONLY JSON like {\"name\":\"...\",\"summary\":\"...\",\"parts\":[{\"shape\":\"fill\",\"block\":\"minecraft:stone\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":4,\"y2\":0,\"z2\":4,\"options\":\"\",\"rows\":[]}]}",
                context, 6000, Design.class);
    }

    @Override
    public LlmResult<ActivityTurn> direct(String context) throws Exception {
        return call(SystemPrompt.DIRECTOR + "\nRespond with ONLY JSON with keys notes, orders, waitFor, waitSeconds, watchArea, done.",
                context, 2000, ActivityTurn.class);
    }

    private <T> LlmResult<T> call(String system, String user, int maxTokens, Class<T> type) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.6);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return new LlmResult<>(null, 0, 0, 0, "HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        long in = 0, out = 0;
        if (json.has("usage") && json.get("usage").isJsonObject()) {
            JsonObject usage = json.getAsJsonObject("usage");
            in = optLong(usage, "prompt_tokens");
            out = optLong(usage, "completion_tokens");
        }
        String content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
        T value = JsonExtract.parse(content, type);
        return new LlmResult<>(value, in, 0, out, value == null ? "unparseable: " + abbreviate(content) : "");
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static long optLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    @Override
    public String describe() {
        return "OpenAI-compatible server " + baseUrl + " (" + model + ")";
    }
}
