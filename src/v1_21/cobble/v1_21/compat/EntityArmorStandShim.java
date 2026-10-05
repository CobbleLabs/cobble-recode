package cobble.v1_21.compat;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;

/** 1.8.9's EntityArmorStand on 1.21.11's ArmorStandEntity. */
public final class EntityArmorStandShim {
    /* 1.8's equipment slots: the held item, then the armour from the boots up. */
    private static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.MAINHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private EntityArmorStandShim() {}

    public static ItemStack getEquipmentInSlot(ArmorStandEntity stand, int slot) {
        return ItemStacks.orNull(stand.getEquippedStack(EQUIPMENT[slot]));
    }
}
