package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;

/** Runs another task for at most a fixed time. */
public final class TimedTask extends Task {
    private final Task inner;
    private final int maxTicks;
    private final String description;

    public TimedTask(Task inner, int maxTicks, String description) {
        this.inner = inner;
        this.maxTicks = maxTicks;
        this.description = description;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (ticks > maxTicks) return Status.SUCCESS;
        Status s = inner.tick(npc);
        return s == Status.FAILURE ? Status.FAILURE : s;
    }

    @Override
    public void stop(NpcBrain npc) {
        inner.stop(npc);
        super.stop(npc);
    }

    @Override
    public String describe() {
        return description;
    }
}
