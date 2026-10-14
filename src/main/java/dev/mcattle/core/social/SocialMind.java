package dev.mcattle.core.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * An NPC's emotional life: a mood made of the eight primary emotions (which fade back to its temperament's
 * baseline over minutes) and a relationship with everyone it has dealt with (which changes with every event
 * and fades only slowly, over real-time days). Persisted with the NPC; fields are public for Gson.
 */
public final class SocialMind {
    public int seed;
    /** Current intensity of each {@link Emotion}, 0..1. */
    public double[] mood = new double[Emotion.COUNT];
    /** Keyed by UUID string (players and NPCs alike). */
    public Map<String, Relationship> relations = new LinkedHashMap<>();
    /** A nuanced feeling the language model put into words ("jealous of Alex's new armor"), shown until it fades. */
    public String feeling = "";
    public long feelingUntil;
    public long lastUpdate;

    private transient Temperament temperament;

    /** Personality constants derived from the seed: how quickly this NPC gets angry, sad, scared, forgives... */
    public record Temperament(double cheer, double temper, double sensitivity, double timidity, double forgiveness, double warmth) {
        public String describe() {
            List<String> t = new ArrayList<>();
            if (temper > 1.25) t.add("short-tempered");
            else if (temper < 0.8) t.add("hard to anger");
            if (sensitivity > 1.2) t.add("sensitive");
            if (timidity > 1.2) t.add("easily scared");
            else if (timidity < 0.8) t.add("brave");
            if (forgiveness > 1.15) t.add("forgiving");
            else if (forgiveness < 0.6) t.add("holds grudges");
            if (warmth > 1.2) t.add("warm");
            if (cheer > 0.28) t.add("upbeat");
            else if (cheer < 0.12) t.add("moody");
            return t.isEmpty() ? "even-tempered" : String.join(", ", t);
        }
    }

    public SocialMind() {
    }

    public SocialMind(int seed) {
        this.seed = seed;
        Temperament t = temperament();
        mood[Emotion.JOY.ordinal()] = t.cheer;
        mood[Emotion.TRUST.ordinal()] = 0.15;
    }

    public Temperament temperament() {
        if (temperament == null) {
            Random r = new Random(seed * 31L + 7);
            temperament = new Temperament(0.05 + r.nextDouble() * 0.3, 0.6 + r.nextDouble(), 0.6 + r.nextDouble() * 0.9,
                    0.6 + r.nextDouble() * 0.8, 0.4 + r.nextDouble() * 1.1, 0.7 + r.nextDouble() * 0.7);
        }
        return temperament;
    }

    private double[] mood() {
        if (mood == null || mood.length != Emotion.COUNT) mood = new double[Emotion.COUNT];
        return mood;
    }

    private Map<String, Relationship> relations() {
        if (relations == null) relations = new LinkedHashMap<>();
        return relations;
    }

    public double get(Emotion e) {
        return mood()[e.ordinal()];
    }

    private double baseline(int i) {
        if (i == Emotion.JOY.ordinal()) return temperament().cheer;
        if (i == Emotion.TRUST.ordinal()) return 0.15;
        if (i == Emotion.ANTICIPATION.ordinal()) return 0.1;
        return 0;
    }

    private double gain(int i) {
        Temperament t = temperament();
        if (i == Emotion.ANGER.ordinal()) return t.temper;
        if (i == Emotion.SADNESS.ordinal()) return t.sensitivity;
        if (i == Emotion.FEAR.ordinal()) return t.timidity;
        return 1;
    }

    // ------------------------------------------------------------------ feelings

    /** Adds an emotion vector (from {@link Emotion#blend} or an event) at the given strength. */
    public void feel(double[] vector, double strength) {
        double[] m = mood();
        for (int i = 0; i < Emotion.COUNT; i++) {
            double d = vector[i] * strength;
            if (d > 0) d *= gain(i) * (1 - m[i] * 0.5); // harder to push an emotion that's already high
            m[i] = Relationship.clamp(m[i] + d, 0, 1);
        }
    }

    public void feel(Emotion e, double amount) {
        double[] v = new double[Emotion.COUNT];
        v[e.ordinal()] = 1;
        feel(v, amount);
    }

    /** Feels a named emotion ("jealous", "grateful"...). False if the word is unknown. */
    public boolean feel(String word, double strength) {
        double[] v = Emotion.blend(word);
        if (v == null) return false;
        feel(v, strength * 0.4);
        return true;
    }

    public void setFeeling(String text, long now, long durationMillis) {
        feeling = text == null ? "" : text.length() > 80 ? text.substring(0, 80) : text;
        feelingUntil = now + durationMillis;
    }

    // ------------------------------------------------------------------ relationships

    public Relationship relation(String key) {
        return relations().get(key);
    }

    public Relationship relation(String key, String name, boolean npc, long now) {
        Relationship r = relations().computeIfAbsent(key, k -> {
            Relationship n = new Relationship();
            n.firstMet = now;
            return n;
        });
        if (name != null && !name.isBlank()) r.name = name;
        r.npc = npc;
        r.lastSeen = now;
        return r;
    }

    public double affinity(String key) {
        Relationship r = relation(key);
        return r == null ? 0 : r.affinity;
    }

    /**
     * Something happened between this NPC and someone else. Changes the mood and the relationship and, for
     * notable things, adds a memory ("gave me 3 diamonds").
     */
    public void event(SocialEvent ev, String key, String name, boolean npc, double strength, String detail, long now) {
        strength = Relationship.clamp(strength, 0, 3);
        Relationship r = relation(key, name, npc, now);
        Temperament t = temperament();

        // the same act feels different depending on who does it
        double emotional = strength;
        if (ev.negative() && r.affinity > 40) feel(Emotion.SADNESS, 0.15 * strength); // hurt by a friend stings
        if (!ev.negative() && ev.affinity > 0 && r.trust < -30) emotional *= 0.5;      // suspicious of enemies bearing gifts
        feel(ev.mood, emotional);

        double like = ev.affinity * strength * (ev.affinity > 0 ? t.warmth : 1);
        if (ev.affinity > 0 && r.trust < -30) like *= 0.5;
        r.affinity = push(r.affinity, like, -100, 100);
        r.trust = push(r.trust, ev.trust * strength, -100, 100);
        r.respect = push(r.respect, ev.respect * strength, -100, 100);
        r.fear = Relationship.clamp(r.fear + ev.fear * strength * t.timidity, 0, 100);
        r.familiarity = Relationship.clamp(r.familiarity + ev.familiarity * strength * (1 - r.familiarity / 110), 0, 100);
        switch (ev) {
            case HIT -> r.hits++;
            case KILLED_ME -> r.kills++;
            case GIFT -> r.gifts++;
            case HELPED, SAVED -> r.helps++;
            case INSULTED -> r.insults++;
            default -> {
            }
        }
        if (ev.memory != null) {
            String text = detail == null || detail.isBlank() ? ev.memory : ev.memory + " " + detail;
            r.remember(now, text, Math.abs(ev.affinity) * strength / 10 + 0.3);
        }
    }

    /** Moves a value toward its limit, more slowly the closer it gets (strong bonds are hard to move). */
    private static double push(double value, double delta, double lo, double hi) {
        if (delta == 0) return value;
        double room = delta > 0 ? (hi - value) / (hi - lo) : (value - lo) / (hi - lo);
        return Relationship.clamp(value + delta * (0.35 + room), lo, hi);
    }

    /** A memory the language model thought worth keeping ("promised to build me a castle"). */
    public void note(String key, String name, boolean npc, String text, long now) {
        relation(key, name, npc, now).remember(now, text, 0.8);
    }

    public void setBond(String key, String name, boolean npc, String bond, long now) {
        relation(key, name, npc, now).bond = bond == null ? "" : bond.length() > 120 ? bond.substring(0, 120) : bond;
    }

    public Relationship byName(String name) {
        for (Relationship r : relations().values()) if (r.name.equalsIgnoreCase(name)) return r;
        return null;
    }

    public String keyOf(String name) {
        for (Map.Entry<String, Relationship> e : relations().entrySet()) if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    /** The relationships that matter most (strong feelings or well known), strongest first. */
    public List<Map.Entry<String, Relationship>> strongest(int max) {
        List<Map.Entry<String, Relationship>> list = new ArrayList<>(relations().entrySet());
        list.sort(Comparator.comparingDouble((Map.Entry<String, Relationship> e) ->
                -(Math.abs(e.getValue().affinity) + e.getValue().fear * 0.5 + e.getValue().familiarity * 0.3)));
        return list.subList(0, Math.min(max, list.size()));
    }

    // ------------------------------------------------------------------ time

    /** Lets emotions settle and grudges, fears and memories fade with time. Call every few seconds. */
    public void tick(long now) {
        if (lastUpdate == 0) {
            lastUpdate = now;
            return;
        }
        double minutes = Math.min(600, (now - lastUpdate) / 60_000.0);
        lastUpdate = now;
        if (minutes <= 0) return;
        Temperament t = temperament();
        double[] m = mood();
        for (int i = 0; i < Emotion.COUNT; i++) {
            double tau = i == Emotion.SURPRISE.ordinal() ? 1.5 : i == Emotion.ANGER.ordinal() ? 4 * t.temper
                    : i == Emotion.SADNESS.ordinal() ? 6 * t.sensitivity : 5;
            double base = baseline(i);
            m[i] = base + (m[i] - base) * Math.exp(-minutes / tau);
        }
        double hours = minutes / 60;
        for (Relationship r : relations().values()) {
            r.fear *= Math.exp(-hours / 24);
            if (r.affinity < 0) r.affinity = Math.min(0, r.affinity + hours * 0.4 * t.forgiveness);   // grudges fade...
            if (r.trust < 0) r.trust = Math.min(0, r.trust + hours * 0.2 * t.forgiveness);            // ...trust slower
            if (r.affinity > 30 && now - r.lastSeen > 3L * 86_400_000) r.affinity -= hours * 0.05;   // drifting apart
            for (Relationship.Episode e : r.episodes()) e.weight *= Math.exp(-hours / (24 * 30));
        }
        if (relations().size() > 48) {
            relations().entrySet().removeIf(e -> e.getValue().familiarity < 5 && Math.abs(e.getValue().affinity) < 5
                    && e.getValue().episodes().isEmpty());
        }
    }

    // ------------------------------------------------------------------ descriptions

    /** The strongest emotions, in words: "annoyed and a bit down". */
    public String moodWords() {
        double[] m = mood();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < Emotion.COUNT; i++) if (m[i] - baseline(i) * 0.6 >= 0.2) idx.add(i);
        idx.sort((a, b) -> Double.compare(m[b], m[a]));
        if (idx.isEmpty()) return m[Emotion.JOY.ordinal()] > 0.15 ? "content" : "calm";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(2, idx.size()); k++) {
            if (k > 0) sb.append(" and ");
            Emotion e = Emotion.values()[idx.get(k)];
            sb.append(e.word(m[idx.get(k)]));
        }
        return sb.toString();
    }

    public String describeMood(long now) {
        StringBuilder sb = new StringBuilder(moodWords());
        double[] m = mood();
        List<String> parts = new ArrayList<>();
        for (Emotion e : Emotion.values()) {
            double v = m[e.ordinal()];
            if (v >= 0.1) parts.add(e.name().toLowerCase() + " " + String.format(java.util.Locale.ROOT, "%.1f", v));
        }
        if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(")");
        if (feeling != null && !feeling.isBlank() && now < feelingUntil) sb.append("; also: ").append(feeling);
        return sb.toString();
    }

    public Emotion dominant() {
        double[] m = mood();
        Emotion best = Emotion.JOY;
        double bestV = -1;
        for (Emotion e : Emotion.values()) {
            double v = m[e.ordinal()] - baseline(e.ordinal());
            if (v > bestV) {
                bestV = v;
                best = e;
            }
        }
        return best;
    }

    /** Positive when feeling good overall, negative when upset. */
    public double valence() {
        double[] m = mood();
        return m[Emotion.JOY.ordinal()] + m[Emotion.TRUST.ordinal()] * 0.5
                - m[Emotion.SADNESS.ordinal()] - m[Emotion.ANGER.ordinal()] - m[Emotion.FEAR.ordinal()] - m[Emotion.DISGUST.ordinal()] * 0.5;
    }
}
