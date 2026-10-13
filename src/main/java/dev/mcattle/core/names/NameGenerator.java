package dev.mcattle.core.names;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Random NPC names: a Minecraft block / item / mob word glued to a real English first name,
 * e.g. "CobbleMaya", "CreeperJack", "BirchOliver". Names are valid player names (max 16 chars, [A-Za-z0-9_]).
 */
public final class NameGenerator {
    static final String[] MINECRAFT_WORDS = {
            "Oak", "Birch", "Spruce", "Jungle", "Acacia", "Cherry", "Mangrove", "Bamboo", "Cobble", "Stone",
            "Gravel", "Sand", "Dirt", "Clay", "Mud", "Moss", "Granite", "Diorite", "Andesite", "Basalt",
            "Tuff", "Calcite", "Obsidian", "Bedrock", "Glass", "Brick", "Terracotta", "Wool", "Iron", "Gold",
            "Copper", "Diamond", "Emerald", "Lapis", "Redstone", "Coal", "Quartz", "Amethyst", "Netherite", "Flint",
            "Torch", "Lantern", "Furnace", "Anvil", "Chest", "Barrel", "Hopper", "Piston", "Lever", "Beacon",
            "Cactus", "Kelp", "Melon", "Pumpkin", "Carrot", "Potato", "Beetroot", "Wheat", "Cookie", "Bread",
            "Cake", "Apple", "Stick", "Bone", "String", "Feather", "Slime", "Magma", "Blaze", "Ghast",
            "Creeper", "Zombie", "Skelly", "Spider", "Ender", "Pearl", "Wither", "Warden", "Allay", "Axolotl",
            "Frog", "Goat", "Llama", "Panda", "Fox", "Wolf", "Ocelot", "Parrot", "Turtle", "Squid",
            "Glow", "Bee", "Honey", "Sheep", "Cow", "Pig", "Chicken", "Rabbit", "Horse", "Mule",
            "Villager", "Piglin", "Hoglin", "Strider", "Shulker", "Phantom", "Drowned", "Husk", "Stray", "Vex",
            "Pickaxe", "Shovel", "Hoe", "Sword", "Shield", "Bow", "Trident", "Saddle", "Compass", "Clock",
            "Map", "Book", "Lead", "Bucket", "Boat", "Minecart", "Rail", "Tnt", "Gunpowder", "Sugar",
            "Snow", "Ice", "Prismarine", "Sponge", "Coral", "Lily", "Fern", "Poppy", "Tulip", "Dandelion",
            "Mycelium", "Podzol", "Nether", "Warped", "Crimson", "Shroom", "Sculk", "Deepslate", "Dripstone", "Totem"
    };

    static final String[] FIRST_NAMES = {
            "James", "Mary", "John", "Linda", "Robert", "Emma", "Michael", "Olivia", "William", "Ava",
            "David", "Sophia", "Richard", "Isabella", "Joseph", "Mia", "Thomas", "Amelia", "Charles", "Harper",
            "Daniel", "Evelyn", "Matthew", "Abigail", "Anthony", "Emily", "Mark", "Ella", "Steven", "Grace",
            "Paul", "Chloe", "Andrew", "Lily", "Joshua", "Zoe", "Kevin", "Nora", "Brian", "Hannah",
            "George", "Lucy", "Edward", "Ruby", "Ryan", "Alice", "Jacob", "Ivy", "Gary", "Rose",
            "Eric", "Clara", "Jonah", "Maya", "Frank", "Stella", "Scott", "Hazel", "Jack", "Violet",
            "Oliver", "Aria", "Henry", "Luna", "Leo", "Layla", "Noah", "Penny", "Liam", "Willow",
            "Ethan", "Nina", "Owen", "Iris", "Lucas", "Ellie", "Mason", "Daisy", "Logan", "Molly",
            "Caleb", "Holly", "Dylan", "Megan", "Nathan", "Paige", "Carl", "Tessa", "Dave", "Wendy",
            "Bob", "Sue", "Tim", "Kate", "Sam", "Jess", "Ben", "Amy", "Tom", "Beth",
            "Fred", "Jane", "Greg", "Anna", "Hank", "Lola", "Ian", "Eve", "Joe", "Ada",
            "Kyle", "Gwen", "Neil", "Fiona", "Phil", "Rita", "Ray", "Tina", "Ted", "Vera",
            "Walt", "Edith", "Hugo", "Martha", "Otto", "Pearl", "Stan", "Doris", "Vince", "Gloria",
            "Barry", "Brenda", "Derek", "Carol", "Gerald", "Janet", "Howard", "Karen", "Kenneth", "Nancy",
            "Larry", "Pam", "Martin", "Sally", "Nigel", "Wanda", "Rupert", "Agnes", "Stanley", "Mabel"
    };

    private final Random random;

    public NameGenerator(Random random) {
        this.random = random;
    }

    /** Total number of distinct names this generator can produce. */
    public static int combinations() {
        int count = 0;
        for (String w : MINECRAFT_WORDS) for (String n : FIRST_NAMES) if (w.length() + n.length() <= 16) count++;
        return count;
    }

    /** Generates a name not present in {@code taken} (case-insensitive). */
    public String generate(Collection<String> taken) {
        Set<String> lower = new java.util.HashSet<>();
        for (String t : taken) lower.add(t.toLowerCase(Locale.ROOT));
        for (int attempt = 0; attempt < 500; attempt++) {
            String word = MINECRAFT_WORDS[random.nextInt(MINECRAFT_WORDS.length)];
            String name = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
            String candidate = random.nextInt(5) == 0 ? name + word : word + name;
            if (candidate.length() <= 16 && !lower.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        // Astronomically unlikely: fall back to a numbered name.
        String base = MINECRAFT_WORDS[random.nextInt(MINECRAFT_WORDS.length)];
        int n = 2;
        while (lower.contains((base + n).toLowerCase(Locale.ROOT))) n++;
        return base + n;
    }

    /** Valid Minecraft player name: 3-16 characters, letters / digits / underscore. */
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    /**
     * Words a player might use to address the NPC: the full name plus the "real name" half of a generated
     * name, e.g. "CobbleMaya" -> ["cobblemaya", "maya"]. The Minecraft half is not used on its own because
     * "stone" or "iron" show up in ordinary sentences all the time.
     */
    public static List<String> nicknames(String name) {
        List<String> out = new ArrayList<>();
        out.add(name.toLowerCase(Locale.ROOT));
        String[] parts = name.split("(?<=[a-z0-9])(?=[A-Z])|_");
        if (parts.length > 1) {
            for (String p : parts) {
                for (String first : FIRST_NAMES) {
                    if (first.equalsIgnoreCase(p)) {
                        out.add(p.toLowerCase(Locale.ROOT));
                        break;
                    }
                }
            }
        }
        return out;
    }
}
