package cobble.v1_21.compat;

import java.util.List;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.RepairableComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;

/**
 * 1.8.9's Item.ToolMaterial as 1.21.11's ToolMaterial. 1.21 items do not keep
 * their material; a tool is of the material whose repair tag it repairs with.
 */
public final class Item_ToolMaterialShim {
    private static final List<ToolMaterial> MATERIALS = List.of(ToolMaterial.WOOD, ToolMaterial.STONE,
            ToolMaterial.COPPER, ToolMaterial.IRON, ToolMaterial.DIAMOND, ToolMaterial.GOLD, ToolMaterial.NETHERITE);
    /* 1.8's names for them; diamond was EMERALD. */
    private static final List<String> NAMES = List.of("WOOD", "STONE", "STONE", "IRON", "EMERALD", "GOLD", "EMERALD");

    private Item_ToolMaterialShim() {}

    static ToolMaterial of(Item item) {
        RepairableComponent repairable = item.getComponents().get(DataComponentTypes.REPAIRABLE);
        if (repairable == null) {
            return null;
        }
        for (ToolMaterial material : MATERIALS) {
            if (repairable.items().getTagKey().filter(material.repairItems()::equals).isPresent()) {
                return material;
            }
        }
        return null;
    }

    static String name(Item item) {
        ToolMaterial material = of(item);
        return material != null ? NAMES.get(MATERIALS.indexOf(material)) : "WOOD";
    }

    public static float getDamageVsEntity(ToolMaterial material) {
        return material != null ? material.attackDamageBonus() : 0.0f;
    }
}
