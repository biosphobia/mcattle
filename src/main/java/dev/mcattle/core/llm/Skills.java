package dev.mcattle.core.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** The skill vocabulary shared by the language model prompt, the built-in parser and the task executor. */
public final class Skills {
    private Skills() {
    }

    public static final String GOTO = "goto";
    public static final String FOLLOW = "follow";
    public static final String STOP = "stop";
    public static final String AUTONOMOUS = "autonomous";
    public static final String OBTAIN = "obtain";
    public static final String MINE = "mine";
    public static final String CHOP = "chop";
    public static final String CRAFT = "craft";
    public static final String SMELT = "smelt";
    public static final String GIVE = "give";
    public static final String DROP = "drop";
    public static final String EQUIP = "equip";
    public static final String ATTACK = "attack";
    public static final String GUARD = "guard";
    public static final String HUNT = "hunt";
    public static final String BUILD = "build";
    public static final String PLACE = "place";
    public static final String DIG = "dig";
    public static final String EXPLORE = "explore";
    public static final String COLLECT = "collect";
    public static final String EAT = "eat";
    public static final String SLEEP = "sleep";
    public static final String EMOTE = "emote";
    public static final String SAY = "say";
    public static final String WAIT = "wait";
    public static final String DEPOSIT = "deposit";
    public static final String WITHDRAW = "withdraw";
    public static final String FARM = "farm";
    public static final String HELP = "help";
    public static final String REPORT = "report";
    public static final String LOOK = "look";
    public static final String ACTIVITY = "activity";
    public static final String USE = "use";
    public static final String BREAK = "break";

    /** name -> one-line documentation (fields: target, item, count, options). */
    public static final Map<String, String> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put(GOTO, "walk/swim/pillar/dig to target ('speaker', a player or NPC name, 'x y z', 'home')");
        CATALOG.put(FOLLOW, "keep following target; options distance=N");
        CATALOG.put(STOP, "cancel everything and stand still until given new orders");
        CATALOG.put(AUTONOMOUS, "go back to playing survival on your own");
        CATALOG.put(OBTAIN, "get count of item by any means: inventory, crafting (with sub-ingredients), mining, chopping, smelting, killing mobs");
        CATALOG.put(MINE, "mine count blocks of item (ore, stone...), finding exposed ones or branch-mining at the right depth");
        CATALOG.put(CHOP, "chop count logs from trees (item = wood type, optional) and replant saplings");
        CATALOG.put(CRAFT, "craft count of item, gathering ingredients first if missing; places a crafting table when needed");
        CATALOG.put(SMELT, "smelt/cook into count of item (item = the result, e.g. minecraft:iron_ingot) using a furnace");
        CATALOG.put(GIVE, "walk to target and toss them count of item (count 0 = all of it, item '*' = whole inventory); gets the item first if missing unless options obtain=false");
        CATALOG.put(DROP, "drop count of item on the ground (item '*' = everything)");
        CATALOG.put(EQUIP, "hold or wear item");
        CATALOG.put(ATTACK, "fight target (player/NPC name or mob type like 'zombie') with real PvP technique; options lethal=true|false (false = stop when target is low), duration=seconds");
        CATALOG.put(GUARD, "stay near target and kill hostile mobs or attackers that come close");
        CATALOG.put(HUNT, "kill count mobs of type item (e.g. 'cow') and pick up the drops");
        CATALOG.put(BUILD, "design and build anything described in text (free-form: 'a cozy spruce cabin with a porch', 'a giant pixel-art creeper', 'a 3x3 tic tac toe board of white wool'); target = where (default: in front of speaker, or 'x y z' corner); options size=small|medium|large; gathers materials in survival; other NPCs join with help");
        CATALOG.put(PLACE, "place one block item at exact target 'x y z' (default: in front of speaker)");
        CATALOG.put(BREAK, "break the block at exact target 'x y z'");
        CATALOG.put(USE, "right-click: hold item (optional) and use it on the block at target 'x y z' (buttons, levers, note blocks, doors, cake, jukebox...) or in the air if no target (fireworks, eat, throw)");
        CATALOG.put(ACTIVITY, "start an open-ended, multi-round activity that you will keep directing (games like tic tac toe, hide and seek, races, parties, shows, roleplay, contests, anything that needs reacting to players over time); text = the goal and rules as you understand them; target = participant NPC names comma separated or 'all'. Give this step to ONE NPC only");
        CATALOG.put(DIG, "dig at target; options shape=hole|tunnel|stairs|room depth=N width=N");
        CATALOG.put(EXPLORE, "wander and look around; options distance=N");
        CATALOG.put(COLLECT, "pick up dropped items nearby");
        CATALOG.put(EAT, "eat the best food in inventory");
        CATALOG.put(SLEEP, "sleep in a nearby or carried bed if it's night");
        CATALOG.put(EMOTE, "body language; options type=greet|wave|dance|jump|spin|nod|shake|sneak|look|flex|bow|panic");
        CATALOG.put(SAY, "say target text in chat mid-plan");
        CATALOG.put(WAIT, "wait count seconds; or wait for the player (target) to do something: options for=chat|block|item|near|far (their answer, a block placed/broken near them, an item tossed to you, coming within radius=N, walking away), count = max seconds (default 180); you are asked what to do next once it happens unless more steps follow (then=think forces that)");
        CATALOG.put(DEPOSIT, "put item ('*' = everything except tools/food) into the nearest chest");
        CATALOG.put(WITHDRAW, "take count of item from nearby chests");
        CATALOG.put(FARM, "harvest grown crops nearby and replant; count = how many");
        CATALOG.put(HELP, "help target NPC with its current task (share its build, gather for it, fight with it)");
        CATALOG.put(REPORT, "say your inventory and status");
        CATALOG.put(LOOK, "look at target");
    }

    public static boolean isKnown(String action) {
        return action != null && CATALOG.containsKey(action.toLowerCase(java.util.Locale.ROOT));
    }
}
