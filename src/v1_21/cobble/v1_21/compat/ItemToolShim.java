package cobble.v1_21.compat;

import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;

/** 1.8.9's ItemTool as a 1.21.11 item in a tool tag, made of whatever {@link Item_ToolMaterialShim} finds. */
public final class ItemToolShim {
    private ItemToolShim() {}

    public static ToolMaterial getToolMaterial(Item item) {
        return Item_ToolMaterialShim.of(item);
    }

    public static String getToolMaterialName(Item item) {
        return Item_ToolMaterialShim.name(item);
    }
}
