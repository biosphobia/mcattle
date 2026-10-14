package dev.mcattle.mixin;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayEntityInvoker {
    @Invoker("setText")
    void mcattle$setText(Text text);

    @Invoker("setBackground")
    void mcattle$setBackground(int argb);

    @Invoker("setLineWidth")
    void mcattle$setLineWidth(int width);
}
