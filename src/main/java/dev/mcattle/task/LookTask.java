package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.math.Vec3d;

public final class LookTask extends Task {
    private final Target target;
    private final int duration;

    public LookTask(Target target, int duration) {
        this.target = target;
        this.duration = duration;
    }

    @Override
    protected Status run(NpcBrain npc) {
        var entity = target.entity(npc);
        if (entity != null) npc.move().lookAt(entity);
        else {
            Vec3d p = target.position(npc);
            if (p == null) return Status.SUCCESS;
            npc.move().lookAt(p.add(0, 1, 0));
        }
        return ticks >= duration ? Status.SUCCESS : Status.RUNNING;
    }

    @Override
    public String describe() {
        return "looking at " + target.describe();
    }
}
