package dev.mcattle.core.social;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lasting personality traits an NPC picks up from what happens to it and what players tell it: "afraid of
 * creepers", "obsessed with diamond blocks", "thinks it's a pirate", "enjoys mining". Each has a strength (how
 * important it is to the NPC) that grows when reinforced and slowly fades when not. Saying the same kind of thing
 * again strengthens the existing trait instead of adding a duplicate. Persisted; fields are public for Gson.
 */
public final class TraitSet {
    public static final int MAX_TRAITS = 40;

    public List<Trait> traits = new ArrayList<>();
    /** Running counts of repeated experiences ("death:creeper" -> 2) that turn into traits past a threshold. */
    public Map<String, Double> experience = new HashMap<>();
    public long lastUpdate;

    public static final class Trait {
        public String text;
        /** 0..1: how much it defines the NPC. */
        public double strength;
        public int reinforced;
        public long since;
        public long lastReinforced;

        public Trait() {
        }

        Trait(String text, long now) {
            this.text = text;
            this.since = now;
            this.lastReinforced = now;
        }

        public String level() {
            return strength >= 0.8 ? "defining" : strength >= 0.5 ? "strong" : strength >= 0.25 ? "mild" : "faint";
        }
    }

    public List<Trait> traits() {
        if (traits == null) traits = new ArrayList<>();
        return traits;
    }

    private Map<String, Double> experience() {
        if (experience == null) experience = new HashMap<>();
        return experience;
    }

    /**
     * Strengthens (amount > 0) or weakens (amount < 0) a trait, adding it if new. Returns the trait, or null if
     * it was weakened away.
     */
    public Trait reinforce(String text, double amount, long now) {
        String clean = clean(text);
        if (clean.isEmpty()) return null;
        Trait t = find(clean);
        if (t == null) {
            if (amount <= 0) return null;
            t = new Trait(clean, now);
            traits().add(t);
        }
        if (amount > 0) {
            t.strength = Math.min(1, t.strength + amount * (1 - t.strength * 0.6)); // the strong get stronger slowly
            t.reinforced++;
            t.lastReinforced = now;
            if (clean.length() > t.text.length() && clean.length() < 70) t.text = clean; // keep the more specific wording
        } else {
            t.strength += amount;
            if (t.strength < 0.05) {
                traits().remove(t);
                return null;
            }
        }
        prune();
        return t;
    }

    /** Removes a trait outright (a player asked it to drop the habit). */
    public boolean remove(String text) {
        Trait t = find(clean(text));
        return t != null && traits().remove(t);
    }

    /**
     * Counts a repeated experience; every time the count passes another multiple of {@code every}, the trait is
     * reinforced. E.g. dying to creepers twice makes it "afraid of creepers".
     */
    public Trait experience(String key, double amount, double every, String trait, double gain, long now) {
        double before = experience().getOrDefault(key, 0.0);
        double after = before + amount;
        experience().put(key, after);
        if (Math.floor(after / every) > Math.floor(before / every)) return reinforce(trait, gain, now);
        return null;
    }

    public double experienceCount(String key) {
        return experience().getOrDefault(key, 0.0);
    }

    public Trait find(String text) {
        String clean = clean(text);
        Trait best = null;
        double bestScore = 0;
        for (Trait t : traits()) {
            double s = similarity(t.text, clean);
            if (s > bestScore) {
                bestScore = s;
                best = t;
            }
        }
        return bestScore >= 0.55 ? best : null;
    }

    /** Old, unreinforced traits fade: weak ones within days, defining ones over months. */
    public void tick(long now) {
        if (lastUpdate == 0) {
            lastUpdate = now;
            return;
        }
        double hours = Math.min(24 * 30, (now - lastUpdate) / 3_600_000.0);
        lastUpdate = now;
        if (hours <= 0) return;
        traits().removeIf(t -> {
            double sinceReinforced = (now - t.lastReinforced) / 3_600_000.0;
            if (sinceReinforced < 6) return false;
            t.strength -= hours * 0.004 * (1.1 - t.strength);
            return t.strength < 0.05;
        });
    }

    private void prune() {
        while (traits().size() > MAX_TRAITS) {
            Trait weakest = null;
            for (Trait t : traits()) if (weakest == null || t.strength < weakest.strength) weakest = t;
            traits().remove(weakest);
        }
    }

    public List<Trait> strongest(int max) {
        List<Trait> list = new ArrayList<>(traits());
        list.sort((a, b) -> Double.compare(b.strength, a.strength));
        return list.subList(0, Math.min(max, list.size()));
    }

    /** "obsessed with diamond blocks (defining), afraid of creepers (strong)". */
    public String describe(int max) {
        StringBuilder sb = new StringBuilder();
        for (Trait t : strongest(max)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.text).append(" (").append(t.level()).append(")");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ text

    /** One change from the model's "traits" field. */
    public record Change(String text, double amount, boolean remove) {
    }

    /**
     * Parses "+likes pink wool; ++obsessed with cake; -afraid of the dark; --talks like a pirate":
     * + strengthen (or add), ++ strongly, - weaken, -- drop entirely. A bare entry counts as +.
     */
    public static List<Change> parse(String field) {
        List<Change> out = new ArrayList<>();
        if (field == null || field.isBlank()) return out;
        for (String part : field.split("[;\\n]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("--")) out.add(new Change(p.substring(2).trim(), 0, true));
            else if (p.startsWith("++")) out.add(new Change(p.substring(2).trim(), 0.5, false));
            else if (p.startsWith("+")) out.add(new Change(p.substring(1).trim(), 0.25, false));
            else if (p.startsWith("-")) out.add(new Change(p.substring(1).trim(), -0.3, false));
            else out.add(new Change(p, 0.25, false));
        }
        out.removeIf(c -> c.text().isBlank());
        return out;
    }

    static String clean(String text) {
        if (text == null) return "";
        String s = text.trim().replaceAll("\\s+", " ").replaceAll("^[\"']|[\"'.]$", "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static final Set<String> STOP = Set.of("a", "an", "the", "of", "to", "with", "and", "is", "it", "its", "it's",
            "really", "very", "so", "about", "for", "in", "on", "by", "at", "be", "being", "thing", "things", "things'");

    private static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.isEmpty() || STOP.contains(w)) continue;
            if (w.length() > 4 && w.endsWith("s")) w = w.substring(0, w.length() - 1); // creepers ~ creeper
            out.add(w);
        }
        return out;
    }

    /** Word-overlap similarity, 0..1 (1 = same meaningful words). */
    static double similarity(String a, String b) {
        Set<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        int common = 0;
        for (String w : wa) if (wb.contains(w)) common++;
        return common / (double) Math.min(wa.size(), wb.size()) * (common / (double) Math.max(wa.size(), wb.size()) * 0.4 + 0.6);
    }
}
