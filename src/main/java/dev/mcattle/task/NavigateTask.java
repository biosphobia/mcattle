package dev.mcattle.task;

import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;

/** Runs the navigator toward a goal; optionally digs through any non-precious block (for tunneling). */
public final class NavigateTask extends Task {
    private final PathFinder.Goal goal;
    private final String description;
    private final boolean tunnel;
    private boolean started;

    public NavigateTask(PathFinder.Goal goal, String description, boolean tunnel) {
        this.goal = goal;
        this.description = description;
        this.tunnel = tunnel;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (!started) {
            started = true;
            npc.nav().navigate(goal);
        }
        Navigator.State s = npc.nav().tick();
        if (s == Navigator.State.ARRIVED) return Status.SUCCESS;
        if (s == Navigator.State.FAILED) return npc.nav().isPartial() ? Status.SUCCESS : fail("can't get there");
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return description;
    }
}
