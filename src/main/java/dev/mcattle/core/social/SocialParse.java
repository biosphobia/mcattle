package dev.mcattle.core.social;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads the compact social fields the language model writes, and guesses the tone of plain chat for free. */
public final class SocialParse {
    private SocialParse() {
    }

    /** One "who:feeling[:strength]" token. who is a name, "self" or "speaker". */
    public record Feel(String who, String word, double strength) {
    }

    public static List<Feel> feelings(String text) {
        List<Feel> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String token : text.trim().split("[\\s,;]+")) {
            String[] p = token.split(":");
            if (p.length < 2 || p[0].isBlank() || p[1].isBlank()) continue;
            double strength = 1;
            if (p.length >= 3) {
                try {
                    strength = Math.max(0.2, Math.min(3, Double.parseDouble(p[2])));
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
            }
            out.add(new Feel(p[0].trim(), p[1].trim().replace('_', ' '), strength));
        }
        return out;
    }

    /** "Steve=frenemies since the diamond incident; Alex=her biggest fan" -> name -> bond. */
    public static Map<String, String> bonds(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String bond = part.substring(eq + 1).trim();
            if (!name.isEmpty()) out.put(name, bond);
        }
        return out;
    }

    private static final Pattern APOLOGY = Pattern.compile("\\b(sorry|my bad|apologi[sz]e|forgive me|didn'?t mean (it|to))\\b");
    private static final Pattern THANKS = Pattern.compile("\\b(thanks|thank you|thx|ty|tysm|appreciate (it|you))\\b");
    private static final Pattern PRAISE = Pattern.compile("\\b(good (job|work|boy|girl|bot)|well done|nice( one| job| work)?|great|awesome|amazing|"
            + "love (you|ya|u)|you'?re (the best|cool|awesome|amazing|great|smart|funny)|best (npc|friend|buddy)|legend|goat|proud of you|cute)\\b");
    private static final Pattern INSULT = Pattern.compile("\\b(stupid|idiot|dumb|useless|trash|garbage|noob|moron|loser|ugly|shut up|stfu|"
            + "hate (you|u|ya)|you suck|u suck|worst|pathetic|annoying|clown|bozo|cringe|get lost|go away|nobody likes you)\\b");
    private static final Pattern THREAT = Pattern.compile("\\b(i('?ll| will) (kill|end|destroy|delete|smite) (you|u)|you'?re dead|watch your back|or else)\\b");
    private static final Pattern FLIRT = Pattern.compile("\\b(marry me|date me|be my (girlfriend|boyfriend|valentine)|you'?re (hot|pretty|handsome|beautiful)|kiss)\\b");

    /** A cheap guess at how a chat line addressed to an NPC would make it feel, or null if neutral. */
    public static SocialEvent tone(String message) {
        if (message == null) return null;
        String m = message.toLowerCase(Locale.ROOT);
        if (THREAT.matcher(m).find()) return SocialEvent.THREATENED;
        if (APOLOGY.matcher(m).find()) return SocialEvent.APOLOGIZED;
        boolean insult = INSULT.matcher(m).find();
        boolean praise = PRAISE.matcher(m).find();
        if (insult && !praise) return SocialEvent.INSULTED;
        if (FLIRT.matcher(m).find()) return SocialEvent.FLIRTED;
        if (praise && !insult) return SocialEvent.PRAISED;
        if (THANKS.matcher(m).find()) return SocialEvent.THANKED;
        return null;
    }
}
