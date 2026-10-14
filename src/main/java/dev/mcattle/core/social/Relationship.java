package dev.mcattle.core.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** How one NPC sees one other player or NPC. Persisted; fields are public for Gson. */
public final class Relationship {
    public String name = "";
    public boolean npc;
    /** -100 (hates) .. 100 (loves). */
    public double affinity;
    /** -100 (expects the worst) .. 100 (would trust with its life). */
    public double trust;
    /** -100 (contempt) .. 100 (looks up to). */
    public double respect;
    /** 0 .. 100: how much it is afraid of them. */
    public double fear;
    /** 0 .. 100: how well they know each other. */
    public double familiarity;
    /** Free-form description of the bond, kept up to date by the language model ("frenemies since the diamond incident"). */
    public String bond = "";
    /** Things that happened between them, most significant kept. */
    public List<Episode> episodes = new ArrayList<>();
    public long firstMet;
    public long lastSeen;
    public int hits, kills, gifts, helps, insults;

    public static final class Episode {
        public long time;
        public String text;
        /** How much it mattered (0..1+); fades slowly. */
        public double weight;
        public int times = 1;

        public Episode() {
        }

        Episode(long time, String text, double weight) {
            this.time = time;
            this.text = text;
            this.weight = weight;
        }
    }

    public List<Episode> episodes() {
        if (episodes == null) episodes = new ArrayList<>();
        return episodes;
    }

    /** A plain label from the numbers: "best friend", "rival", "nemesis"... */
    public String label() {
        if (familiarity < 3 && Math.abs(affinity) < 5) return "stranger";
        if (affinity <= -60) return fear > 50 ? "feared enemy" : "nemesis";
        if (affinity <= -30) return respect > 20 ? "rival" : "enemy";
        if (affinity < -8) return respect > 25 ? "rival" : "someone it dislikes";
        if (affinity >= 70) return trust >= 50 ? "best friend" : "close friend";
        if (affinity >= 35) return "friend";
        if (affinity >= 12) return familiarity > 20 ? "pal" : "friendly acquaintance";
        return familiarity > 15 ? "acquaintance" : "new face";
    }

    void remember(long now, String text, double weight) {
        if (text == null || text.isBlank()) return;
        List<Episode> list = episodes();
        for (Episode e : list) {
            if (e.text.equalsIgnoreCase(text)) {
                e.times++;
                e.time = now;
                e.weight = Math.min(3, e.weight + weight * 0.6);
                return;
            }
        }
        list.add(new Episode(now, text, weight));
        if (list.size() > 12) {
            // forget the least significant memory (older ones count a little less)
            Episode weakest = null;
            double low = Double.MAX_VALUE;
            for (Episode e : list) {
                double score = e.weight * e.times / (1 + (now - e.time) / 86_400_000.0 / 7);
                if (score < low) {
                    low = score;
                    weakest = e;
                }
            }
            list.remove(weakest);
        }
    }

    /** One compact line for the language model. */
    public String describe(int maxEpisodes) {
        StringBuilder sb = new StringBuilder();
        sb.append(label()).append(" (like ").append(Math.round(affinity)).append(", trust ").append(Math.round(trust))
                .append(", respect ").append(Math.round(respect));
        if (fear >= 10) sb.append(", fear ").append(Math.round(fear));
        sb.append(", knows them ").append(familiarity < 10 ? "barely" : familiarity < 40 ? "somewhat" : "well").append(")");
        if (bond != null && !bond.isBlank()) sb.append("; bond: ").append(bond);
        List<Episode> top = new ArrayList<>(episodes());
        top.sort((a, b) -> Double.compare(b.weight * b.times, a.weight * a.times));
        if (!top.isEmpty()) {
            sb.append("; remembers they: ");
            for (int i = 0; i < Math.min(maxEpisodes, top.size()); i++) {
                if (i > 0) sb.append(", ");
                Episode e = top.get(i);
                sb.append(e.text);
                if (e.times > 1) sb.append(" (x").append(e.times).append(")");
            }
        }
        return sb.toString();
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return name.toLowerCase(Locale.ROOT) + ": " + describe(3);
    }
}
