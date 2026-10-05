package cobble.v1_21.compat;

import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** 1.8.9's Item on 1.21.11's. 1.21 has no item subtypes; ids are its raw registry ids. */
public final class ItemShim {
    private ItemShim() {}

    public static Registry<Item> itemRegistry() {
        return Registries.ITEM;
    }

    public static Item getByNameOrId(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id != null && Registries.ITEM.containsId(id)) {
            return Registries.ITEM.get(id);
        }
        try {
            return Item.byRawId(Integer.parseInt(name));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Absent getCreativeTab(Item item) {
        return null;
    }

    /* The one stack an item has on 1.21 is all its subtypes. */
    public static void getSubItems(Item item, Item same, Absent tab, List<ItemStack> into) {
        into.add(new ItemStack(item));
    }

    public static boolean getHasSubtypes(Item item) {
        return false;
    }

    public static int getIdFromItem(Item item) {
        return Item.getRawId(item);
    }

    public static Item getItemFromBlock(Block block) {
        return block.asItem();
    }

    public static float getStrVsBlock(Item item, ItemStack stack, Block block) {
        return item.getMiningSpeed(stack, block.getDefaultState());
    }

    public static String getUnlocalizedName(Item item) {
        return item.getTranslationKey();
    }

    public static boolean isDamageable(Item item) {
        return item.getDefaultStack().isDamageable();
    }
}
