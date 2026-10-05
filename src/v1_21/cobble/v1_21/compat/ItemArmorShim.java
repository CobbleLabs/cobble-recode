package cobble.v1_21.compat;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

/** 1.8.9's ItemArmor as a 1.21.11 item worn in an armour slot. */
public final class ItemArmorShim {
    private ItemArmorShim() {}

    /** 1.8 numbered the pieces from the helmet down. */
    public static int armorType(Item item) {
        EquipmentSlot slot = ItemTypes.armorSlot(item);
        if (slot == null) {
            return -1;
        }
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            default -> 3;
        };
    }

    /** Null for armour 1.8 had no material for, netherite say. */
    public static ArmorMaterial getArmorMaterial(Item item) {
        String id = Registries.ITEM.getId(item).getPath();
        if (id.startsWith("leather_")) {
            return ArmorMaterial.LEATHER;
        }
        if (id.startsWith("chainmail_")) {
            return ArmorMaterial.CHAIN;
        }
        if (id.startsWith("iron_")) {
            return ArmorMaterial.IRON;
        }
        if (id.startsWith("golden_")) {
            return ArmorMaterial.GOLD;
        }
        return id.startsWith("diamond_") ? ArmorMaterial.DIAMOND : null;
    }

    public static int damageReduceAmount(Item item) {
        return (int) Math.round(ItemTypes.modifier(item, EntityAttributes.ARMOR));
    }
}
