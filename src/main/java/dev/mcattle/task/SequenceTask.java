package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Runs tasks one after another; fails as soon as one fails. */
public final class SequenceTask extends Task {
    private final Deque<Task> remaining;
    private final String description;

    public SequenceTask(List<Task> tasks, String description) {
        this.remaining = new ArrayDeque<>(tasks);
        this.description = description;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (!hasChild()) {
            Task next = remaining.poll();
            if (next == null) return Status.SUCCESS;
            setChild(next, npc);
        }
        Status s = tickChild(npc);
        if (s == Status.FAILURE) return Status.FAILURE;
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return description;
    }
}
