package dev.mcattle.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything that defines an NPC across restarts. Inventory, position and stats live in vanilla player data. */
public final class NpcIdentity {
    public String name;
    public String uuid;
    /** Preset key or "account:Name". */
    public String skin = "steve";
    public String textureValue;
    public String textureSignature;
    public String persona;
    public String owner;
    public String homeDimension = "minecraft:overworld";
    public int homeX, homeY, homeZ;
    public boolean autonomous = true;
    /** True once vanilla has saved player data for this NPC, so its position is restored on load. */
    public boolean spawnedBefore;

    // ---- long-term memory (persisted with the NPC) ----
    /** Lasting instructions and facts players gave this NPC, oldest first. */
    public List<String> memories = new ArrayList<>();
    /** Extra names players gave it ("your name is Bob"). */
    public List<String> aliases = new ArrayList<>();
    /** Behavior overrides; null = server default. */
    public Integer socialness;
    public Boolean retaliate;
    public Integer chatty;
    /** Player name to follow / guard whenever it has nothing else to do. */
    public String follow;
    public String guard;
    /** Canned lines rewritten in the NPC's speaking style (category -> lines). */
    public Map<String, List<String>> styleLines;
    /** Feelings and relationships with players and other NPCs. */
    public dev.mcattle.core.social.SocialMind social;

    public List<String> memories() {
        if (memories == null) memories = new ArrayList<>();
        return memories;
    }

    public List<String> aliases() {
        if (aliases == null) aliases = new ArrayList<>();
        return aliases;
    }

    public UUID uuid() {
        return UUID.fromString(uuid);
    }
}
