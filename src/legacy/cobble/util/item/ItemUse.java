package cobble.util.item;

import cobble.module.movement.NoSlowDown;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/** The kind of use NoSlowDown sorts a held stack into. */
public final class ItemUse {
    private ItemUse() {}

    public static NoSlowDown.Use of(ItemStack stack) {
        if (stack == null) {
            return NoSlowDown.Use.OTHER;
        }
        if (stack.getItem() instanceof ItemSword) {
            return NoSlowDown.Use.BLOCK;
        }
        if (stack.getItem() instanceof ItemFood
                || stack.getItem() instanceof ItemPotion && !ItemPotion.isSplash(stack.getItemDamage())) {
            return NoSlowDown.Use.CONSUME;
        }
        return stack.getItem() instanceof ItemBow ? NoSlowDown.Use.BOW : NoSlowDown.Use.OTHER;
    }
}
