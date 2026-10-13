package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;

/** Says what the NPC carries and how it's doing. */
public final class ReportTask extends Task {
    @Override
    protected Status run(NpcBrain npc) {
        var p = npc.npc();
        npc.say(String.format("hp %d, food %d | %s", (int) Math.ceil(p.getHealth()), p.getHungerManager().getFoodLevel(),
                npc.inventorySummary(12)));
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return "reporting";
    }
}
