package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import org.jetbrains.annotations.Nullable;

/**
 * A unit of NPC behavior. Tasks are ticked by the brain until they report success or failure, and can delegate
 * to a child task (e.g. "give" delegates to "obtain", which delegates to "craft", which delegates to "chop").
 */
public abstract class Task {
    public enum Status {RUNNING, SUCCESS, FAILURE}

    private boolean started;
    @Nullable private Task child;
    @Nullable protected String failureReason;
    protected int ticks;

    public final Status tick(NpcBrain npc) {
        if (!started) {
            started = true;
            start(npc);
        }
        ticks++;
        return run(npc);
    }

    protected void start(NpcBrain npc) {
    }

    protected abstract Status run(NpcBrain npc);

    /** Called when the task is cancelled or replaced. */
    public void stop(NpcBrain npc) {
        if (child != null) {
            child.stop(npc);
            child = null;
        }
        npc.nav().stop();
        npc.hands().cancelBreaking();
        npc.hands().releaseItem();
    }

    /** Short human-readable description, used in chat and in the language-model situation report. */
    public abstract String describe();

    /** Description including what the child task is doing. */
    public String describeDeep() {
        return child == null ? describe() : describe() + " > " + child.describeDeep();
    }

    @Nullable
    public String failureReason() {
        if (failureReason != null) return failureReason;
        return null;
    }

    protected Status fail(String reason) {
        this.failureReason = reason;
        return Status.FAILURE;
    }

    // ------------------------------------------------------------- children

    protected void setChild(@Nullable Task task, NpcBrain npc) {
        if (child != null) child.stop(npc);
        child = task;
    }

    protected boolean hasChild() {
        return child != null;
    }

    /** Ticks the child; when it finishes it is cleared and its status returned. */
    protected Status tickChild(NpcBrain npc) {
        if (child == null) return Status.SUCCESS;
        Status s = child.tick(npc);
        if (s != Status.RUNNING) {
            if (s == Status.FAILURE && failureReason == null) failureReason = child.failureReason();
            child = null;
        }
        return s;
    }

    @Nullable
    protected Task child() {
        return child;
    }

    /** A fresh task another NPC can run to help with this one, or null if helping makes no sense. */
    @Nullable
    public Task helpCopy(NpcBrain helper) {
        return null;
    }

    /** True for long-running "keep doing" tasks (follow, guard) that new orders should simply replace. */
    public boolean isOpenEnded() {
        return false;
    }
}
