package cobble.module.player;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/** Cobble's ArmorSwitch: one press swaps between two armor sets. */
public final class ArmorSwitch extends Module {
    private static final List<String> MATERIALS = List.of("Diamond", "Gold", "Iron", "Leather", "Chain");
    private static final ItemArmor.ArmorMaterial[] MATERIAL_TYPES = {
        ItemArmor.ArmorMaterial.DIAMOND, ItemArmor.ArmorMaterial.GOLD, ItemArmor.ArmorMaterial.IRON,
        ItemArmor.ArmorMaterial.LEATHER, ItemArmor.ArmorMaterial.CHAIN,
    };

    private final Setting set1 = addChoice("Set 1", "One of the two armor sets", MATERIALS, 0);
    private final Setting set2 = addChoice("Set 2", "The other armor set", MATERIALS, 2);
    private final Setting onInventoryOpen = addBoolean("On Inventory Open", "Switch whenever you open the inventory",
            false);
    private final Setting preferProtection = addBoolean("Prefer Protection", "Of two pieces, the better enchanted",
            true);
    private final ArmorEquipper equipper = new ArmorEquipper(this);
    private boolean inventoryWasOpen;

    public ArmorSwitch() {
        super("ArmorSwitch", "Swaps between two armor sets", Category.PLAYER, Subcategory.MEDIUM);
    }

    @Override
    public String suffix() {
        return onInventoryOpen.bool() ? "Inv Open" : set1.choiceName() + " / " + set2.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        equipper.stop();
        inventoryWasOpen = mc.currentScreen instanceof GuiInventory;
        if (!onInventoryOpen.bool() && mc.thePlayer != null && mc.currentScreen == null) {
            equipper.start(plan(), false);
        }
    }

    @Override
    public void onDisable(Context context) {
        equipper.stop();
    }

    @Override
    public void onTick(Context context) {
        if (mc.thePlayer == null) {
            return;
        }
        if (equipper.running()) {
            if (!equipper.tick() && !onInventoryOpen.bool()) {
                setEnabled(false, context);
            }
            return;
        }
        if (!onInventoryOpen.bool()) {
            setEnabled(false, context);
            return;
        }
        boolean open = mc.currentScreen instanceof GuiInventory;
        if (open && !inventoryWasOpen) {
            equipper.start(plan(), true);
        }
        inventoryWasOpen = open;
    }

    @Override
    public void onRender(Context context) {
        equipper.render();
    }

    /* Wear more of set 1 and it goes to set 2, otherwise to set 1. */
    private int[] plan() {
        EntityPlayerSP player = mc.thePlayer;
        ItemArmor.ArmorMaterial first = MATERIAL_TYPES[set1.choice()];
        ItemArmor.ArmorMaterial second = MATERIAL_TYPES[set2.choice()];
        int firstWorn = 0;
        int secondWorn = 0;
        for (int type = 0; type < 4; ++type) {
            ItemStack worn = worn(type);
            firstWorn += is(worn, first, type) ? 1 : 0;
            secondWorn += is(worn, second, type) ? 1 : 0;
        }
        ItemArmor.ArmorMaterial target = firstWorn > secondWorn ? second : first;

        int[] sources = new int[4];
        for (int type = 0; type < 4; ++type) {
            ItemStack current = worn(type);
            int source = best(type, target);
            /* The same piece already on: only worth it if the spare is better. */
            boolean upgrade = source >= 0
                    && (!is(current, target, type) || better(player.inventory.getStackInSlot(source), current));
            sources[type] = upgrade ? source : -1;
        }
        return sources;
    }

    /* Type 0 is the helmet, which the inventory keeps last. */
    private ItemStack worn(int type) {
        return mc.thePlayer.inventory.armorItemInSlot(3 - type);
    }

    private static boolean is(ItemStack stack, ItemArmor.ArmorMaterial material, int type) {
        return stack != null && stack.getItem() instanceof ItemArmor armor && armor.getArmorMaterial() == material
                && armor.armorType == type;
    }

    private int best(int type, ItemArmor.ArmorMaterial material) {
        int bestSlot = -1;
        for (int slot = 0; slot < 36; ++slot) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (is(stack, material, type)
                    && (bestSlot < 0 || better(stack, mc.thePlayer.inventory.getStackInSlot(bestSlot)))) {
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /* Protection first when preferred, then the less worn of the two. */
    private boolean better(ItemStack candidate, ItemStack against) {
        if (preferProtection.bool()) {
            int difference = protection(candidate) - protection(against);
            if (difference != 0) {
                return difference > 0;
            }
        }
        return candidate.getItemDamage() < against.getItemDamage();
    }

    private static int protection(ItemStack stack) {
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack);
    }
}
