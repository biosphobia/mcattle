package dev.mcattle.task;

import dev.mcattle.control.ItemResolver;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.Locale;

final class TaskUtil {
    private TaskUtil() {
    }

    static String name(ItemStack stack) {
        return ItemResolver.describe(stack);
    }

    static String pretty(net.minecraft.item.Item item) {
        return Registries.ITEM.getId(item).getPath().replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
