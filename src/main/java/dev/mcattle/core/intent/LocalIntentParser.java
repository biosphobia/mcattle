package dev.mcattle.core.intent;

import dev.mcattle.core.llm.Orders.Step;
import dev.mcattle.core.llm.Skills;
import dev.mcattle.core.names.NameGenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the most common requests without any language model, so simple orders cost zero tokens and run
 * instantly ("stop", "follow me", "give me 5 wood", "attack me", "build a house"...). Anything it cannot map
 * confidently is reported as not understood and handed to the language model.
 */
public final class LocalIntentParser {
    private static final Pattern EVERYONE = Pattern.compile(
            "\\b(everyone|everybody|all of you|you all|y'?all|you guys|guys|team|all npcs|npcs|bots|all)\\b");
    private static final String[] FILLERS = {
            "hey", "hi there", "yo", "ok", "okay", "please", "pls", "plz", "can you", "could you", "would you",
            "will you", "can u", "could u", "i want you to", "i need you to", "i'd like you to", "id like you to",
            "go ahead and", "i order you to", "now", "right now", "quickly", "asap", "real quick", "for me", "thanks",
            "thank you", "dude", "bro", "mate", "buddy"
    };
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
            Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("fifteen", 15), Map.entry("twenty", 20), Map.entry("thirty", 30),
            Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("hundred", 100), Map.entry("couple", 2),
            Map.entry("few", 3), Map.entry("some", 0), Map.entry("lots", 16), Map.entry("bunch", 16),
            Map.entry("stack", 64), Map.entry("half stack", 32), Map.entry("dozen", 12));
    private static final List<String> STRUCTURES = List.of("house", "hut", "cabin", "tower", "wall", "bridge",
            "pyramid", "platform", "pillar", "fort", "igloo", "statue");
    private static final Map<String, String> STRUCTURE_ALIASES = Map.of("home", "house", "castle", "fort",
            "shelter", "hut", "base", "house", "mansion", "house", "shack", "hut", "skyscraper", "tower",
            "fortress", "fort", "monument", "statue", "floor", "platform");
    private static final Map<String, String> EMOTES = Map.ofEntries(
            Map.entry("dance", "dance"), Map.entry("jump", "jump"), Map.entry("spin", "spin"), Map.entry("wave", "wave"),
            Map.entry("nod", "nod"), Map.entry("crouch", "sneak"), Map.entry("sneak", "sneak"), Map.entry("twerk", "sneak"),
            Map.entry("squat", "sneak"), Map.entry("bow", "bow"), Map.entry("flex", "flex"), Map.entry("panic", "panic"),
            Map.entry("shake your head", "shake"), Map.entry("say hi", "greet"), Map.entry("say hello", "greet"),
            Map.entry("greet", "greet"));

    private LocalIntentParser() {
    }

    /**
     * @param message    raw chat / transcript text
     * @param npcNames   names of all NPCs (used to detect who is addressed)
     * @param playerNames online player names (for "give it to Alex")
     */
    public static ParsedCommand parse(String message, Collection<String> npcNames, Collection<String> playerNames) {
        String text = " " + normalize(message) + " ";

        List<String> addressed = new ArrayList<>();
        for (String npc : npcNames) {
            for (String nick : NameGenerator.nicknames(npc)) {
                Pattern p = Pattern.compile("\\b" + Pattern.quote(nick) + "\\b");
                Matcher m = p.matcher(text);
                if (m.find()) {
                    if (!addressed.contains(npc)) addressed.add(npc);
                    text = m.replaceAll(" ");
                }
            }
        }
        boolean everyone = false;
        Matcher all = EVERYONE.matcher(text);
        // "all" alone is too common ("give me all your wood"); only count it at the start of the sentence.
        while (all.find()) {
            String word = all.group(1);
            if (!word.equals("all") || text.trim().startsWith("all ")) {
                everyone = true;
                text = text.substring(0, all.start()) + " " + text.substring(all.end());
                all = EVERYONE.matcher(text);
            }
        }
        boolean question = message.trim().endsWith("?") || text.trim().matches(
                "^(what|why|how|where|who|when|which|do you|are you|did you|have you|is it|is there|can i|should i|whats|what's|hows|how's|wanna|do u|r u)\\b.*");
        for (String filler : FILLERS) text = text.replaceAll("\\b" + Pattern.quote(filler) + "\\b", " ");
        text = text.replaceAll("\\s+", " ").trim();
        // "go chop trees" -> "chop trees", but keep "go home" / "go away"
        text = text.replaceFirst("^(go|go and|and)\\s+(?!home\\b|away\\b|to\\b)", "");

        boolean complex = text.matches(".*\\b(then|after that|afterwards|while|unless|until|before|but)\\b.*")
                || text.matches(".*\\b(and)\\b.*\\b(and|then)\\b.*")
                || text.split(" ").length > 14;

        List<Step> steps = question ? null : understand(text, playerNames);
        if (steps != null && text.matches(".*\\band\\b.*") && !text.matches(".*\\b(black and white|salt and pepper)\\b.*")) {
            // "chop wood and build a house": the parser only got the first half right
            complex = true;
        }
        return new ParsedCommand(addressed, everyone, steps, complex, question, text);
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[^a-z0-9_':#\\- ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<Step> understand(String t, Collection<String> playerNames) {
        if (t.isEmpty()) return null;
        Matcher m;

        if (t.matches("^(stop|halt|freeze|stay|stay here|stay put|wait|wait here|hold on|hold up|cancel|stand still|chill|enough|cut it out|knock it off|quit it|that's enough|thats enough)( it| that| everything)?$"))
            return List.of(Step.of(Skills.STOP));
        if (t.matches("^(follow|follow me|come with me|stick with me|stay with me|tag along|keep up|come along)$"))
            return List.of(Step.of(Skills.FOLLOW, "speaker", "", 0, ""));
        if ((m = Pattern.compile("^follow (\\w+)$").matcher(t)).matches())
            return List.of(Step.of(Skills.FOLLOW, resolvePerson(m.group(1), playerNames), "", 0, ""));
        if (t.matches("^(come|come here|come to me|come over here|over here|get over here|here|come back|tp to me|to me|on me|regroup)$"))
            return List.of(Step.of(Skills.GOTO, "speaker", "", 0, ""));
        if (t.matches("^(go home|go back home|return home|head home|back to base|go back to base)$"))
            return List.of(Step.of(Skills.GOTO, "home", "", 0, ""));
        if (t.matches("^(play|go play|do your thing|do whatever|do what you want|carry on|continue|you're free|youre free|be free|free roam|resume|keep going|go on|have fun|dismissed|go away|leave me alone)$"))
            return List.of(Step.of(Skills.AUTONOMOUS));
        if (t.matches("^(explore|wander|roam|go explore|look around|scout|scout around|adventure)( around)?$"))
            return List.of(Step.of(Skills.EXPLORE, "", "", 0, "distance=96"));
        if (t.matches("^(sleep|go to sleep|go to bed|bed time|bedtime|take a nap|nap)$"))
            return List.of(Step.of(Skills.SLEEP));
        if (t.matches("^(eat|eat something|eat food|have a snack|go eat)$"))
            return List.of(Step.of(Skills.EAT));
        if (t.matches("^(pick up|collect|grab|pick) (the )?(items|stuff|drops|loot|everything)( up)?$"))
            return List.of(Step.of(Skills.COLLECT));
        if (t.matches("^(what do you have|whats in your inventory|what's in your inventory|inventory|show inventory|report|status|how are you doing|show me your stuff|what you got|what have you got)$"))
            return List.of(Step.of(Skills.REPORT));
        if (t.matches("^(drop|empty|dump|toss) (everything|it all|your inventory|your stuff|all your stuff|all of it|all)$"))
            return List.of(Step.of(Skills.DROP, "", "*", 0, ""));
        if (t.matches("^(farm|harvest|harvest the crops|harvest crops|tend the farm|do some farming)$"))
            return List.of(Step.of(Skills.FARM, "", "", 32, ""));

        for (Map.Entry<String, String> emote : EMOTES.entrySet()) {
            if (t.matches("^(do a |do the |)" + emote.getKey() + "( for me| around| a bit| dance)?$"))
                return List.of(Step.of(Skills.EMOTE, "speaker", "", 0, "type=" + emote.getValue()));
        }

        // guard / protect
        if ((m = Pattern.compile("^(guard|protect|defend|watch over|cover|bodyguard) (\\w+)$").matcher(t)).matches())
            return List.of(Step.of(Skills.GUARD, resolvePerson(m.group(2), playerNames), "", 0, ""));

        // fights
        if ((m = Pattern.compile("^(attack|fight|kill|hit|punch|slay|murder|destroy|beat up|duel|pvp|spar with|spar|1v1|fight with) (me|\\w+)( to the death| for real| seriously)?( please)?$").matcher(t)).matches()) {
            String verb = m.group(1);
            boolean lethal = m.group(3) != null || verb.equals("kill") || verb.equals("murder") || verb.equals("slay");
            if (verb.startsWith("spar")) lethal = false;
            String who = resolvePerson(m.group(2), playerNames);
            List<Step> plan = new ArrayList<>();
            plan.add(Step.of(Skills.EQUIP, "", "#minecraft:swords", 0, ""));
            plan.add(Step.of(Skills.ATTACK, who, "", 0, "lethal=" + lethal));
            return plan;
        }
        if ((m = Pattern.compile("^(hunt|kill|go hunt|slay) (\\S+ )?(\\w+?)s?$").matcher(t)).matches()) {
            int n = parseCount(m.group(2), 3);
            return List.of(Step.of(Skills.HUNT, "", m.group(3), n, ""));
        }

        // give / bring
        if ((m = Pattern.compile("^(give|bring|toss|throw|hand|pass|send|drop) (me|us|him|her|them|\\w+) (.+)$").matcher(t)).matches()) {
            String who = resolvePerson(m.group(2), playerNames);
            Quantity q = quantity(m.group(3), 0);
            if (q.item.isBlank()) return null;
            if (q.item.matches("(everything|all|it all|all your stuff|your stuff|your items|all your items|your inventory)"))
                return List.of(Step.of(Skills.GIVE, who, "*", 0, ""));
            return List.of(Step.of(Skills.GIVE, who, q.item, q.count == 0 ? 1 : q.count, ""));
        }
        if ((m = Pattern.compile("^(get|fetch|bring|grab) (.+?) (for|to) (me|us|\\w+)$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 1);
            return List.of(Step.of(Skills.GIVE, resolvePerson(m.group(4), playerNames), q.item, q.count == 0 ? 1 : q.count, ""));
        }

        // build
        if ((m = Pattern.compile("^(build|make|construct|create) (me |us )?(a |an |the |some )?(.*?)\\b(" + String.join("|", allStructureWords()) + ")s?\\b(.*)$").matcher(t)).matches()) {
            String adjectives = m.group(4).trim();
            String structure = canonicalStructure(m.group(5));
            String rest = m.group(6).trim();
            StringBuilder options = new StringBuilder("structure=").append(structure);
            String material = null;
            Matcher mat = Pattern.compile("(out of|made of|made from|from|with|using) (.+)$").matcher(rest);
            if (mat.find()) material = mat.group(2);
            else if (!adjectives.isEmpty()) material = adjectives.replaceAll("\\b(big|huge|large|small|tiny|little|nice|cool|cozy|tall|giant|massive|simple|quick|beautiful|epic|good|great)\\b", "").trim();
            if (material != null && !material.isBlank()) options.append(" material=").append(material.trim().replace(' ', '_'));
            if (adjectives.matches(".*\\b(big|huge|large|giant|massive|tall)\\b.*")) options.append(" size=11");
            if (adjectives.matches(".*\\b(small|tiny|little)\\b.*")) options.append(" size=5");
            return List.of(Step.of(Skills.BUILD, "speaker", "", 0, options.toString()));
        }

        // craft
        if ((m = Pattern.compile("^(craft|make|create) (.+)$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 1);
            if (q.item.isBlank()) return null;
            return List.of(Step.of(Skills.CRAFT, "", q.item, Math.max(1, q.count), ""));
        }
        // smelt
        if ((m = Pattern.compile("^(smelt|cook) (.+)$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 0);
            return List.of(Step.of(Skills.SMELT, "", q.item, q.count, ""));
        }
        // chop
        if ((m = Pattern.compile("^(chop|cut|cut down|chop down|punch|harvest|collect|get|gather) (.*?)(trees?|wood|logs?)( down)?$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2).trim() + " log", 16);
            String type = q.item.replaceAll("\\b(some|the|a|log)\\b", "").trim();
            return List.of(Step.of(Skills.CHOP, "", type.isEmpty() ? "" : type + "_log", q.count == 0 ? 16 : q.count, ""));
        }
        // mine
        if ((m = Pattern.compile("^(mine|dig up|dig for|go mining for|mine for|strip mine for|branch mine for) (.+)$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 8);
            return List.of(Step.of(Skills.MINE, "", q.item, q.count == 0 ? 8 : q.count, ""));
        }
        if (t.matches("^(mine|go mining|go mine|strip mine|branch mine|go caving|caving)$"))
            return List.of(Step.of(Skills.MINE, "", "#minecraft:iron_ores", 8, ""));
        // dig
        if ((m = Pattern.compile("^dig (a )?(hole|tunnel|staircase|stairs|room|down)( .*)?$").matcher(t)).matches()) {
            String shape = switch (m.group(2)) {
                case "staircase", "stairs" -> "stairs";
                case "down" -> "hole";
                default -> m.group(2);
            };
            return List.of(Step.of(Skills.DIG, "speaker", "", 0, "shape=" + shape));
        }
        // obtain
        if ((m = Pattern.compile("^(get|gather|collect|fetch|find|obtain|grab|farm) (.+)$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 8);
            if (q.item.isBlank()) return null;
            return List.of(Step.of(Skills.OBTAIN, "", q.item, q.count == 0 ? 8 : q.count, ""));
        }
        // equip
        if ((m = Pattern.compile("^(equip|hold|wear|put on|use) (your |a |an |the )?(.+)$").matcher(t)).matches())
            return List.of(Step.of(Skills.EQUIP, "", m.group(3), 0, ""));
        // put in chest
        if ((m = Pattern.compile("^(put|store|deposit|stash) (.+?) in(to)? (the |a )?chest$").matcher(t)).matches()) {
            String what = m.group(2).matches("(everything|all|it all|your stuff|all your stuff|it|stuff|things)") ? "*" : m.group(2);
            return List.of(Step.of(Skills.DEPOSIT, "", what, 0, ""));
        }
        if ((m = Pattern.compile("^(take|get|grab) (.+?) (out of|from) (the |a )?chest$").matcher(t)).matches()) {
            Quantity q = quantity(m.group(2), 64);
            return List.of(Step.of(Skills.WITHDRAW, "", q.item, q.count == 0 ? 64 : q.count, ""));
        }
        if ((m = Pattern.compile("^(go to|walk to|head to|go over to|run to) (.+)$").matcher(t)).matches()) {
            String where = m.group(2).trim();
            if (where.matches("-?\\d+ -?\\d+ -?\\d+")) return List.of(Step.of(Skills.GOTO, where, "", 0, ""));
            if (where.matches("\\w+")) return List.of(Step.of(Skills.GOTO, resolvePerson(where, playerNames), "", 0, ""));
            return null;
        }
        if ((m = Pattern.compile("^(look at|stare at|watch) (me|\\w+)$").matcher(t)).matches())
            return List.of(Step.of(Skills.LOOK, resolvePerson(m.group(2), playerNames), "", 0, ""));
        return null;
    }

    private static List<String> allStructureWords() {
        List<String> words = new ArrayList<>(STRUCTURES);
        words.addAll(STRUCTURE_ALIASES.keySet());
        return words;
    }

    private static String canonicalStructure(String word) {
        return STRUCTURE_ALIASES.getOrDefault(word, word);
    }

    private static String resolvePerson(String word, Collection<String> playerNames) {
        if (word.equals("me") || word.equals("us") || word.equals("i")) return "speaker";
        for (String p : playerNames) if (p.equalsIgnoreCase(word)) return p;
        return word;
    }

    record Quantity(int count, String item) {
    }

    static int parseCount(String word, int fallback) {
        if (word == null) return fallback;
        word = word.trim();
        if (word.isEmpty()) return fallback;
        if (word.matches("\\d+")) return Math.min(Integer.parseInt(word), 2304);
        Integer n = NUMBER_WORDS.get(word);
        return n == null ? fallback : n;
    }

    /** "5 wood blocks" -> (5, "wood blocks"); "a stack of cobble" -> (64, "cobble"); "some bread" -> (fallback, "bread"). */
    static Quantity quantity(String phrase, int fallback) {
        String p = phrase.trim().replaceAll("^(your|my|the)\\s+", "");
        int count = fallback;
        Matcher m = Pattern.compile("^(\\d+|a stack|stack|half a stack|a half stack|a dozen|dozen|a couple|couple|a few|few|lots|a lot|a bunch|bunch|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|hundred|a|an|some)\\s+(of\\s+)?(.*)$").matcher(p);
        if (m.matches()) {
            String w = m.group(1).replaceFirst("^a ", "").replaceFirst("^half a stack$", "half stack");
            if (w.equals("a lot")) w = "lots";
            count = w.equals("some") ? fallback : parseCount(w, fallback);
            p = m.group(3);
            // "2 stacks of" / "3 dozen"
            Matcher mult = Pattern.compile("^(stacks?|dozens?)\\s+(of\\s+)?(.*)$").matcher(p);
            if (mult.matches()) {
                count *= mult.group(1).startsWith("stack") ? 64 : 12;
                p = mult.group(3);
            }
        }
        p = p.replaceAll("\\b(please|pls|now|real quick)\\b", "").replaceAll("\\s+", " ").trim();
        return new Quantity(count, p);
    }
}
