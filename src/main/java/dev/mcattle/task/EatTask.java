package dev.mcattle.task;

import dev.mcattle.npc.NpcBrain;
import net.minecraft.util.Hand;

/** Eats until (nearly) full or out of decent food. */
public final class EatTask extends Task {
    private final boolean healing;
    private int eaten;
    private int useTicks;

    public EatTask(boolean healing) {
        this.healing = healing;
    }

    @Override
    protected Status run(NpcBrain npc) {
        var p = npc.npc();
        if (p.isUsingItem()) {
            useTicks++;
            npc.nav().stop();
            return useTicks > 60 ? Status.SUCCESS : Status.RUNNING;
        }
        int food = p.getHungerManager().getFoodLevel();
        boolean wantMore = healing ? p.getHealth() < p.getMaxHealth() - 4 && eaten < 3 : food < 18 && eaten < 4;
        if (!wantMore && eaten > 0) return Status.SUCCESS;
        if (!wantMore && !healing && food >= 18) return Status.SUCCESS;
        boolean selected = healing ? npc.inventory().selectHealingFood() : npc.inventory().selectFood(food <= 6);
        if (!selected) return eaten > 0 ? Status.SUCCESS : fail("i've got nothing to eat");
        if (!p.canConsume(false) && !p.getMainHandStack().isFood()) return Status.SUCCESS;
        npc.hands().useItem(Hand.MAIN_HAND);
        useTicks = 0;
        if (p.isUsingItem()) eaten++;
        else return eaten > 0 ? Status.SUCCESS : fail("can't eat right now");
        return Status.RUNNING;
    }

    @Override
    public String describe() {
        return "eating";
    }
}
