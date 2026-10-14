package dev.mcattle.mixin;

import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    /** Who dropped or threw the item (null for block drops and mob loot). */
    @Accessor("thrower")
    UUID mcattle$getThrower();
}
