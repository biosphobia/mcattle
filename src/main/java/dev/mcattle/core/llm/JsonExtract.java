package dev.mcattle.core.llm;

import com.google.gson.Gson;

/** Lenient JSON extraction for models that wrap their answer in prose or markdown fences. */
public final class JsonExtract {
    private static final Gson GSON = new Gson();

    private JsonExtract() {
    }

    public static <T> T parse(String text, Class<T> type) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return GSON.fromJson(text.substring(start, end + 1), type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
