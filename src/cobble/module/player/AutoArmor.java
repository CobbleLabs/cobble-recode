package cobble.module.player;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.player.inventorymanager.ArmorScorer;
import net.minecraft.client.gui.inventory.GuiInventory;

/** Puts on the best armor carried whenever it beats what is worn. */
public final class AutoArmor extends Module {
    /* A run the server did not take waits this long before the next, or a server
     * refusing the clicks would get the inventory opened every tick. */
    private static final long RUN_PAUSE_MS = 500;

    private final Setting onInventoryOpen = addBoolean("On Inventory Open", "Only while you have the inventory open",
            false);
    private final ArmorEquipper equipper = new ArmorEquipper(this);
    private long pausedUntil;

    public AutoArmor() {
        super("AutoArmor", "Puts on the best armor you carry", Category.PLAYER, Subcategory.BASIC);
    }

    @Override
    public String suffix() {
        return onInventoryOpen.bool() ? "Inv Open" : "";
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
        long now = System.currentTimeMillis();
        if (equipper.running()) {
            if (!equipper.tick()) {
                pausedUntil = now + RUN_PAUSE_MS;
            }
            return;
        }
        boolean inventory = mc.currentScreen instanceof GuiInventory;
        if (now >= pausedUntil && (inventory || !onInventoryOpen.bool() && mc.currentScreen == null)) {
            equipper.start(upgrades(), inventory);
        }
    }

    @Override
    public void onRender(Context context) {
        equipper.render();
    }

    /* Per armor type, the inventory slot of the best piece that beats the worn one, or -1. */
    private int[] upgrades() {
        int[] sources = new int[4];
        for (int type = 0; type < 4; ++type) {
            sources[type] = ArmorScorer.findUpgradeSlot(mc.thePlayer.inventory.mainInventory,
                    mc.thePlayer.inventory.armorItemInSlot(3 - type), type, true);
        }
        return sources;
    }
}
