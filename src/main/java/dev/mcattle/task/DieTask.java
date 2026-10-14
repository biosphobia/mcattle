package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Dies on purpose because a player said so ("kill yourself", "go jump in lava"). With how=lava it walks into
 * lava if there is some nearby; otherwise it simply drops dead like a player typing /kill. It respawns as usual.
 */
public final class DieTask extends Task {
    private final String how;
    @Nullable private BlockPos lava;
    private boolean searched;

    public DieTask(String how) {
        this.how = how == null ? "" : how;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (!npc.npc().isAlive()) return Status.SUCCESS;
        if (how.equalsIgnoreCase("lava")) {
            if (!searched) {
                searched = true;
                List<BlockPos> found = new BlockSearch(npc.world(), npc.npc().getBlockPos(), 32, 16,
                        s -> s.isOf(Blocks.LAVA), (p, s) -> true, 1).runNow();
                if (!found.isEmpty()) {
                    lava = found.get(0);
                    npc.nav().navigate(PathFinder.near(lava, 1.5));
                }
            }
            if (lava != null && ticks < 20 * 60) {
                Navigator.State s = npc.nav().tick();
                if (s == Navigator.State.ARRIVED || s == Navigator.State.FAILED || npc.pos().squaredDistanceTo(Vec3d.ofCenter(lava)) < 6) {
                    npc.nav().stop();
                    npc.move().moveTo(Vec3d.ofBottomCenter(lava), false); // step in
                    if (npc.npc().isOnGround() && npc.npc().horizontalCollision) npc.move().jump();
                }
                if (ticks < 20 * 60) return Status.RUNNING;
            }
        }
        npc.npc().kill(); // like /kill
        return Status.SUCCESS;
    }

    @Override
    public String describe() {
        return how.equalsIgnoreCase("lava") ? "heading for the lava" : "dying dramatically";
    }
}
