package cobble.v1_21.compat;

import net.minecraft.item.Item;

/** 1.8.9's ItemSword as a 1.21.11 item in the sword tag. */
public final class ItemSwordShim {
    private ItemSwordShim() {}

    /* 1.8 counted the bare hand's point of damage in; 1.21's modifier leaves it to the attribute's base. */
    public static float getDamageVsEntity(Item item) {
        return (float) ItemTypes.attackDamage(item) + 1.0f;
    }
}
