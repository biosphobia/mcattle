package dev.mcattle.task;

import dev.mcattle.control.BlockSearch;
import dev.mcattle.control.InteractionControl;
import dev.mcattle.control.InventoryControl;
import dev.mcattle.control.ItemQuery;
import dev.mcattle.control.Navigator;
import dev.mcattle.control.PathFinder;
import dev.mcattle.npc.NpcBrain;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Puts items into, or takes items out of, the nearest chest / barrel. */
public final class ChestTask extends Task {
    private final boolean deposit;
    private final ItemQuery query;
    private final int count;
    @Nullable private BlockPos chest;
    private boolean navigating;
    private int openTicks = -1;

    public ChestTask(boolean deposit, ItemQuery query, int count) {
        this.deposit = deposit;
        this.query = query;
        this.count = count;
    }

    @Override
    protected Status run(NpcBrain npc) {
        if (chest == null) {
            List<BlockPos> found = new BlockSearch(npc.world(), npc.npc().getBlockPos(), 24, 8,
                    s -> s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock,
                    (p, s) -> !deposit || hasMatching(npc, p), 1).runNow();
            if (found.isEmpty()) return fail(deposit ? "there's no chest around" : "no chest nearby has " + query.describe());
            chest = found.get(0);
        }
        if (!npc.hands().inReach(chest)) {
            if (!navigating) {
                npc.nav().navigate(PathFinder.reach(chest, InteractionControl.REACH - 1));
                navigating = true;
            }
            if (npc.nav().tick() == Navigator.State.FAILED) return fail("can't reach the chest");
            return Status.RUNNING;
        }
        npc.nav().stop();
        npc.move().lookAt(Vec3d.ofCenter(chest));
        BlockState state = npc.world().getBlockState(chest);
        if (openTicks < 0) {
            npc.world().addSyncedBlockEvent(chest, state.getBlock(), 1, 1); // lid opens for everyone watching
            openTicks = 0;
            return Status.RUNNING;
        }
        if (++openTicks < 10) return Status.RUNNING;
        Inventory inv = inventoryAt(npc, chest);
        if (inv == null) return fail("that chest is blocked");
        int moved = deposit ? depositInto(npc, inv) : withdrawFrom(npc, inv);
        npc.world().addSyncedBlockEvent(chest, state.getBlock(), 1, 0);
        inv.markDirty();
        if (moved == 0) return fail(deposit ? "chest is full" : "no " + query.describe() + " in there");
        return Status.SUCCESS;
    }

    @Nullable
    private static Inventory inventoryAt(NpcBrain npc, BlockPos pos) {
        BlockState state = npc.world().getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getInventory(chestBlock, state, npc.world(), pos, true);
        }
        return npc.world().getBlockEntity(pos) instanceof Inventory inv ? inv : null;
    }

    private boolean hasMatching(NpcBrain npc, BlockPos pos) {
        return true;
    }

    private int depositInto(NpcBrain npc, Inventory chest) {
        int moved = 0;
        List<ItemStack> main = npc.npc().getInventory().main;
        int limit = count <= 0 ? Integer.MAX_VALUE : count;
        for (int i = 0; i < main.size() && moved < limit; i++) {
            ItemStack s = main.get(i);
            if (s.isEmpty() || !query.test(s)) continue;
            if (query.isAny() && (npc.inventory().isTool(s) || InventoryControl.foodScore(s) >= 1)) continue; // keep tools & food
            int n = Math.min(s.getCount(), limit - moved);
            ItemStack part = s.split(n);
            ItemStack rest = HopperBlockEntity.transfer(null, chest, part, null);
            moved += n - rest.getCount();
            if (!rest.isEmpty()) {
                s.increment(rest.getCount());
                break;
            }
        }
        return moved;
    }

    private int withdrawFrom(NpcBrain npc, Inventory chest) {
        int moved = 0;
        int limit = count <= 0 ? Integer.MAX_VALUE : count;
        for (int i = 0; i < chest.size() && moved < limit; i++) {
            ItemStack s = chest.getStack(i);
            if (s.isEmpty() || !query.test(s)) continue;
            int n = Math.min(s.getCount(), limit - moved);
            ItemStack part = chest.removeStack(i, n);
            moved += part.getCount();
            npc.inventory().insert(part);
        }
        return moved;
    }

    @Override
    public String describe() {
        return (deposit ? "storing " : "taking ") + query.describe() + (deposit ? " in a chest" : " from a chest");
    }
}
