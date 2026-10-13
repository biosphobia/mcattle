package dev.mcattle.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up signed skin textures of Minecraft accounts from Mojang's public API. Vanilla clients only render
 * textures carrying Mojang's signature, which is why skins are copied from real accounts instead of uploaded.
 * Results are cached on disk so each account is fetched once.
 */
public final class SkinService {
    public record Texture(String value, String signature) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Texture> cache = new ConcurrentHashMap<>();
    private final Path cacheFile;

    public SkinService(Path cacheFile) {
        this.cacheFile = cacheFile;
        if (Files.exists(cacheFile)) {
            try (Reader r = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                Map<String, Texture> loaded = GSON.fromJson(r, new TypeToken<Map<String, Texture>>() {
                }.getType());
                if (loaded != null) cache.putAll(loaded);
            } catch (Exception ignored) {
            }
        }
    }

    public CompletableFuture<Optional<Texture>> fetch(String account) {
        String key = account.toLowerCase(Locale.ROOT);
        Texture cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> idResponse = http.send(HttpRequest.newBuilder(
                                URI.create("https://api.mojang.com/users/profiles/minecraft/" + account))
                        .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (idResponse.statusCode() != 200) return Optional.<Texture>empty();
                String id = JsonParser.parseString(idResponse.body()).getAsJsonObject().get("id").getAsString();
                HttpResponse<String> profile = http.send(HttpRequest.newBuilder(
                                URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                        .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (profile.statusCode() != 200) return Optional.<Texture>empty();
                JsonArray properties = JsonParser.parseString(profile.body()).getAsJsonObject().getAsJsonArray("properties");
                for (int i = 0; i < properties.size(); i++) {
                    JsonObject p = properties.get(i).getAsJsonObject();
                    if ("textures".equals(p.get("name").getAsString()) && p.has("signature")) {
                        Texture t = new Texture(p.get("value").getAsString(), p.get("signature").getAsString());
                        cache.put(key, t);
                        save();
                        return Optional.of(t);
                    }
                }
            } catch (Exception e) {
                System.err.println("[mcattle] skin lookup for " + account + " failed: " + e);
            }
            return Optional.<Texture>empty();
        });
    }

    private synchronized void save() {
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer w = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8)) {
                GSON.toJson(cache, w);
            }
        } catch (Exception ignored) {
        }
    }
}
