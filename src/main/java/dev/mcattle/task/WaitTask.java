package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;

public final class WaitTask extends Task {
    private final int duration;

    public WaitTask(int ticks) {
        this.duration = ticks;
    }

    @Override
    protected Status run(NpcBrain npc) {
        return ticks >= duration ? Status.SUCCESS : Status.RUNNING;
    }

    @Override
    public String describe() {
        return "waiting";
    }
}
