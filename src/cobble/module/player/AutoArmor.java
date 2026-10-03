package cobble.module.player;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.player.inventorymanager.ArmorScorer;
import cobble.module.shared.Facing;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/** Puts on the best armor carried whenever it beats what is worn. */
public final class AutoArmor extends Module {
    /* A run the server did not take waits this long before the next, or a server
     * refusing the clicks would get the inventory opened every tick. */
    private static final long RUN_PAUSE_MS = 500;
    /* A hit taken or swung holds off equipping this many ticks, and longer while still squared up. */
    private static final int COMBAT_TICKS = 20;

    private final Setting onInventoryOpen = addBoolean("On Inventory Open", "Only while you have the inventory open",
            false);
    private final Setting combatCheck = addBoolean("Combat Check", "Won't equip armor while in combat", false);
    private final ArmorEquipper equipper = new ArmorEquipper(this);
    private long pausedUntil;
    private int combatTicks;
    private float lastHealth = -1.0f;
    private World lastWorld;

    public AutoArmor() {
        super("AutoArmor", "Puts on the best armor you carry", Category.PLAYER, Subcategory.BASIC);
    }

    @Override
    public String suffix() {
        if (inCombat()) {
            return "In Combat";
        }
        return onInventoryOpen.bool() ? "Inv Open" : "";
    }

    @Override
    public void onDisable(Context context) {
        equipper.stop();
        combatTicks = 0;
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        trackCombat(player);
        long now = System.currentTimeMillis();
        if (equipper.running()) {
            if (!equipper.tick()) {
                pausedUntil = now + RUN_PAUSE_MS;
            }
            return;
        }
        boolean inventory = mc.currentScreen instanceof GuiInventory;
        if (now >= pausedUntil && !inCombat()
                && (inventory || !onInventoryOpen.bool() && mc.currentScreen == null)) {
            equipper.start(upgrades(), inventory);
        }
    }

    @Override
    public void onRender(Context context) {
        equipper.render();
    }

    private boolean inCombat() {
        return combatCheck.bool() && combatTicks > 0;
    }

    private void trackCombat(EntityPlayerSP player) {
        if (player.worldObj != lastWorld) {
            lastWorld = player.worldObj;
            lastHealth = -1.0f;
            combatTicks = 0;
        }
        float health = player.getHealth();
        MovingObjectPosition pointed = mc.objectMouseOver;
        boolean hurt = health < lastHealth || player.hurtTime == player.maxHurtTime && player.maxHurtTime > 0;
        boolean swinging = player.isSwingInProgress && pointed != null && pointed.entityHit != null;
        lastHealth = health;
        if (hurt || swinging) {
            combatTicks = COMBAT_TICKS;
        } else if (combatTicks > 0 && --combatTicks == 0
                && (Facing.opponentFacing(player) || Facing.facingOpponent(player))) {
            combatTicks = COMBAT_TICKS;
        }
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
