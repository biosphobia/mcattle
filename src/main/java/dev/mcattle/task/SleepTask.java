package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.control.Tags;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Goes to bed at night: a free bed nearby, or one from the inventory placed on the spot. */
public final class SleepTask extends Task {
    @Nullable private BlockPos bed;
    private boolean navigating;
    private boolean closer;
    private int attempts;

    @Override
    protected Status run(NpcBrain npc) {
        var self = npc.npc();
        if (self.isSleeping()) {
            npc.nav().stop();
            return npc.world().isDay() && ticks > 100 ? Status.SUCCESS : Status.RUNNING;
        }
        if (npc.world().isDay() && !npc.world().isThundering()) return ticks < 5 ? fail("it's not night yet") : Status.SUCCESS;
        if (hasChild()) {
            Status s = tickChild(npc);
            if (s == Status.RUNNING) return Status.RUNNING;
            if (s == Status.FAILURE) return fail("no bed and can't place one");
        }
        if (bed == null) {
            List<BlockPos> beds = new BlockSearch(npc.world(), self.getBlockPos(), 24, 8,
                    s -> s.isIn(Tags.BEDS) && s.get(BedBlock.PART) == BedPart.HEAD && !s.get(BedBlock.OCCUPIED), null, 1).runNow();
            if (!beds.isEmpty()) bed = beds.get(0);
            else if (npc.inventory().has(s -> s.isIn(Tags.ITEM_BEDS))) {
                PlaceNearTask place = new PlaceNearTask(ItemQuery.tag(Tags.ITEM_BEDS, "bed", Items.WHITE_BED));
                setChild(place, npc);
                return Status.RUNNING;
            } else {
                return fail("i don't have a bed");
            }
        }
        BlockState state = npc.world().getBlockState(bed);
        if (!state.isIn(Tags.BEDS)) {
            bed = null;
            return ++attempts > 3 ? fail("bed's gone") : Status.RUNNING;
        }
        if (!npc.hands().inReach(bed) || closer && npc.pos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(bed)) > 4) {
            if (!navigating) {
                npc.nav().navigate(PathFinder.reach(bed, closer ? 2.0 : InteractionControl.REACH - 1.5));
                navigating = true;
            }
            if (npc.nav().tick() == Navigator.State.FAILED) return fail("can't reach the bed");
            return Status.RUNNING;
        }
        npc.nav().stop();
        var result = self.trySleep(bed);
        if (result.left().isPresent()) {
            String why = String.valueOf(result.left().get());
            if (why.contains("NOT_SAFE")) return fail("can't sleep, there are monsters nearby");
            if (why.contains("NOT_POSSIBLE_NOW")) return fail("it's not night yet");
            if (why.contains("TOO_FAR") || why.contains("OBSTRUCTED")) {
                navigating = false;
                closer = true;
                return ++attempts > 4 ? fail("can't get into that bed") : Status.RUNNING;
            }
            return fail("can't sleep right now");
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(NpcBrain npc) {
        super.stop(npc);
        if (npc.npc().isSleeping()) npc.npc().wakeUp();
    }

    @Override
    public String describe() {
        return "going to sleep";
    }
}
