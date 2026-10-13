package dev.mcattle.npc;

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

    public UUID uuid() {
        return UUID.fromString(uuid);
    }
}
