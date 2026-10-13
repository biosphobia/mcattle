package dev.mcattle.control;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** "Which items count": one exact item, a tag (any log), or a category (any food, any sword). */
public final class ItemQuery implements Predicate<ItemStack> {
    private final String description;
    private final Predicate<ItemStack> predicate;
    /** Concrete item to produce when the NPC has to make/find one; null if it must be chosen from the tag. */
    @Nullable private final Item preferred;
    @Nullable private final TagKey<Item> tag;

    private ItemQuery(String description, Predicate<ItemStack> predicate, @Nullable Item preferred, @Nullable TagKey<Item> tag) {
        this.description = description;
        this.predicate = predicate;
        this.preferred = preferred;
        this.tag = tag;
    }

    public static ItemQuery of(Item item) {
        return new ItemQuery(Registries.ITEM.getId(item).getPath().replace('_', ' '), s -> s.isOf(item), item, null);
    }

    public static ItemQuery tag(TagKey<Item> tag, String description, @Nullable Item preferred) {
        return new ItemQuery(description, s -> s.isIn(tag), preferred, tag);
    }

    public static ItemQuery category(String description, Predicate<ItemStack> predicate, @Nullable Item preferred) {
        return new ItemQuery(description, predicate, preferred, null);
    }

    public static final ItemQuery ANY = new ItemQuery("everything", s -> !s.isEmpty(), null, null);

    @Override
    public boolean test(ItemStack stack) {
        return !stack.isEmpty() && predicate.test(stack);
    }

    public boolean isAny() {
        return this == ANY;
    }

    @Nullable
    public Item preferred() {
        return preferred;
    }

    @Nullable
    public TagKey<Item> tag() {
        return tag;
    }

    /** All registered items this query accepts (for choosing what to craft/mine). */
    public List<Item> candidates() {
        List<Item> list = new ArrayList<>();
        if (preferred != null) list.add(preferred);
        for (Item item : Registries.ITEM) {
            if (item != preferred && predicate.test(new ItemStack(item))) list.add(item);
        }
        return list;
    }

    public String describe() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }
}
