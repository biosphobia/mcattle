package dev.mcattle.ai;

import dev.mcattle.npc.NpcBrain;
import dev.mcattle.task.Task;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A participant's side of an {@link ActivitySession}: runs the steps the director hands out, idles in between. */
public final class ActivityTask extends Task {
    private final ActivitySession session;
    private final NpcBrain npc;
    private final Deque<Task> steps = new ArrayDeque<>();
    @Nullable private Task current;

    ActivityTask(ActivitySession session, NpcBrain npc) {
        this.session = session;
        this.npc = npc;
    }

    public ActivitySession session() {
        return session;
    }

    void assign(List<Task> tasks, boolean replace) {
        if (replace) {
            if (current != null) current.stop(npc);
            current = null;
            for (Task t : steps) t.stop(npc);
            steps.clear();
        }
        steps.addAll(tasks);
    }

    boolean idle() {
        return current == null && steps.isEmpty();
    }

    @Override
    protected Status run(NpcBrain brain) {
        if (current == null) current = steps.poll();
        if (current == null) {
            if (session.isFinishing()) return Status.SUCCESS;
            // nothing to do this round: watch the host like an attentive player
            ServerPlayerEntity host = session.host();
            if (host != null && host.getWorld() == brain.npc().getWorld() && host.distanceTo(brain.npc()) < 24) brain.move().lookAt(host);
            return Status.RUNNING;
        }
        Status s = current.tick(brain);
        if (s != Status.RUNNING) {
            current.stop(brain);
            if (s == Status.FAILURE) session.onStepsDone(brain, current.failureReason() == null ? current.describe() : current.failureReason());
            current = null;
            if (steps.isEmpty()) session.onStepsDone(brain, null);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(NpcBrain brain) {
        if (current != null) current.stop(brain);
        current = null;
        steps.clear();
        super.stop(brain);
        session.leave(brain);
    }

    @Override
    public String describe() {
        String g = session.goal();
        return "activity: " + (g.length() > 60 ? g.substring(0, 60) + "..." : g)
                + (current != null ? " (now: " + current.describeDeep() + ")" : "");
    }

    @Override
    public boolean isOpenEnded() {
        return true;
    }
}
