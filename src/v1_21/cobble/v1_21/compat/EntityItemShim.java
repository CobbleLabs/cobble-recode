package cobble.v1_21.compat;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;

/** 1.8.9's EntityItem on 1.21.11's ItemEntity. */
public final class EntityItemShim {
    private EntityItemShim() {}

    public static ItemStack getEntityItem(ItemEntity entity) {
        return ItemStacks.orNull(entity.getStack());
    }
}
