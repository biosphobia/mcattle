package dev.mcattle.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.class)
public interface DisplayEntityInvoker {
    @Invoker("setBillboardMode")
    void mcattle$setBillboardMode(DisplayEntity.BillboardMode mode);

    @Invoker("setTransformation")
    void mcattle$setTransformation(AffineTransformation transformation);

    @Invoker("setViewRange")
    void mcattle$setViewRange(float range);
}
