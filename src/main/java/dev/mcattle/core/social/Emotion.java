package dev.mcattle.core.social;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The eight primary emotions (Plutchik). Every other feeling is a blend of these: love is joy + trust, jealousy
 * is anger + fear + sadness, pride is joy + anger, and so on ({@link #blend(String)}).
 */
public enum Emotion {
    JOY, SADNESS, ANGER, FEAR, TRUST, DISGUST, SURPRISE, ANTICIPATION;

    public static final int COUNT = values().length;

    private static final Map<String, double[]> WORDS = new HashMap<>();

    private static void word(double joy, double sadness, double anger, double fear, double trust, double disgust,
                             double surprise, double anticipation, String... names) {
        double[] v = {joy, sadness, anger, fear, trust, disgust, surprise, anticipation};
        for (String n : names) WORDS.put(n, v);
    }

    static {
        //   joy  sad  ang  fear trust disg surp antic
        word(1.0, 0, 0, 0, 0, 0, 0, 0, "happy", "joy", "glad", "cheerful", "joyful", "pleased");
        word(0.8, 0, 0, 0, 0, 0, 0.6, 0, "delighted", "thrilled", "overjoyed");
        word(1.0, 0, 0, 0, 0, 0, 0.3, 0.6, "excited", "hyped", "ecstatic");
        word(0.4, 0, 0, 0, 0.3, 0, 0, 0, "content", "calm", "relaxed", "peaceful", "satisfied");
        word(0.7, 0, 0, 0, 0.8, 0, 0, 0, "love", "loving", "affectionate", "adoring", "fond");
        word(0.6, 0, 0, 0, 0.6, 0, 0, 0.3, "grateful", "thankful");
        word(0.7, 0, 0.3, 0, 0, 0, 0, 0.2, "proud", "smug", "confident");
        word(0.6, 0, 0, 0, 0, 0, 0, 0.7, "hopeful", "optimistic", "eager");
        word(0.5, 0, 0, 0.2, 0.3, 0, 0.3, 0.2, "flirty", "crush", "smitten", "infatuated", "romantic");
        word(0.6, 0, 0, 0, 0.2, 0, 0.3, 0.3, "amused", "playful", "silly", "goofy");
        word(0.3, 0, 0, 0, 0.6, 0, 0, 0, "trusting", "loyal", "safe", "accepted");
        word(0.2, 0, 0, 0, 0.3, 0, 0.3, 0.6, "curious", "interested", "intrigued");
        word(0.3, 0, 0, 0.4, 0.3, 0, 0.7, 0, "awe", "amazed", "impressed", "admiring");
        word(0, 0, 0, 0, 0, 0, 1.0, 0, "surprised", "shocked", "startled", "stunned");
        word(0, 1.0, 0, 0, 0, 0, 0, 0, "sad", "unhappy", "down", "blue", "sorrowful");
        word(0, 0.8, 0, 0, 0, 0, 0, 0, "lonely", "alone", "abandoned", "homesick");
        word(0, 0.9, 0, 0.2, 0, 0, 0, 0, "heartbroken", "grieving", "devastated", "hurt");
        word(0, 0.6, 0, 0, 0, 0, 0.5, 0, "disappointed", "let down");
        word(0, 0.7, 0, 0.6, 0, 0, 0, 0, "hopeless", "despair", "miserable");
        word(0, 0.5, 0, 0, 0, 0.5, 0, 0, "guilty", "remorseful", "sorry", "regretful");
        word(0, 0.4, 0, 0.4, 0, 0.4, 0, 0, "embarrassed", "ashamed", "humiliated", "awkward");
        word(0.1, 0.3, 0, 0, 0, 0.2, 0, 0, "bored", "meh", "unimpressed");
        word(0, 0.5, 0.6, 0, 0, 0, 0, 0, "envious", "bitter", "resentful", "sulky");
        word(0, 0.3, 0.6, 0.4, 0, 0, 0, 0.2, "jealous", "possessive");
        word(0, 0, 1.0, 0, 0, 0, 0, 0, "angry", "mad", "furious", "enraged", "livid");
        word(0, 0, 0.5, 0, 0, 0.2, 0, 0, "annoyed", "irritated", "grumpy", "salty", "frustrated");
        word(0, 0, 0.8, 0, 0, 0, 0, 0.6, "aggressive", "vengeful", "spiteful", "competitive");
        word(0, 0, 0.8, 0, 0, 0, 0.6, 0, "outraged", "offended", "insulted");
        word(0, 0, 0.6, 0, 0, 0.7, 0, 0, "contempt", "contemptuous", "scornful", "disgusted");
        word(0, 0, 0, 0, 0, 1.0, 0, 0, "grossed out", "repulsed");
        word(0, 0, 0, 1.0, 0, 0, 0, 0, "scared", "afraid", "terrified", "frightened");
        word(0, 0, 0, 0.6, 0, 0, 0, 0.6, "anxious", "nervous", "worried", "stressed");
        word(0, 0, 0, 0.5, 0.4, 0, 0, 0, "shy", "submissive", "timid");
        word(0, 0.2, 0, 0.3, 0, 0, 0.4, 0, "confused", "lost");
        word(0, 0.3, 0.3, 0, -0.6, 0, 0.3, 0, "betrayed", "suspicious", "distrustful");
        word(0.4, 0, 0, -0.3, 0.3, 0, 0, 0, "brave", "relieved");
    }

    /** The emotion vector for a feeling word ("jealous", "grateful"...), or null if unknown. */
    public static double[] blend(String word) {
        if (word == null) return null;
        String w = word.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        double[] v = WORDS.get(w);
        if (v == null && w.endsWith("ed")) v = WORDS.get(w.substring(0, w.length() - 1)); // "loved" -> "love"
        if (v == null) {
            for (Emotion e : values()) if (e.name().equalsIgnoreCase(w)) {
                v = new double[COUNT];
                v[e.ordinal()] = 1;
            }
        }
        return v;
    }

    /** Names for a strong / moderate / mild amount of each primary emotion. */
    public String word(double intensity) {
        String[] w = switch (this) {
            case JOY -> new String[]{"ecstatic", "happy", "cheerful"};
            case SADNESS -> new String[]{"miserable", "sad", "a bit down"};
            case ANGER -> new String[]{"furious", "angry", "annoyed"};
            case FEAR -> new String[]{"terrified", "scared", "uneasy"};
            case TRUST -> new String[]{"devoted", "trusting", "at ease"};
            case DISGUST -> new String[]{"disgusted", "fed up", "unimpressed"};
            case SURPRISE -> new String[]{"stunned", "surprised", "puzzled"};
            case ANTICIPATION -> new String[]{"fired up", "eager", "curious"};
        };
        return intensity > 0.7 ? w[0] : intensity > 0.4 ? w[1] : w[2];
    }
}
