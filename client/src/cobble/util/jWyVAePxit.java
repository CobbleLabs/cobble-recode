package cobble.util;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;

public final class jWyVAePxit {
    private jWyVAePxit() {
    }

    public static boolean isHoldingItemType(EntityLivingBase var0, Class<? extends Item> var1) {
        return var0.getHeldItem() != null && var1.isInstance(var0.getHeldItem().getItem());
    }
}
