package cobble.module.player;

import cobble.event.LoadWorldEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.util.InventoryCursor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;

/**
 * Cobble's AutoRefill: fills the empty hotbar slots (and the soup bowls) with
 * soup and Splash Instant Health II from the rest of the inventory.
 *
 * <p>Packet opens the inventory on the server only, holding the movement keys
 * for the moment it takes to open; Legit opens the real inventory screen and
 * glides the cursor onto each item before moving it.
 * Manual runs once per toggle; Automatic refills whenever the hotbar runs out
 * of healing; On Inventory Open refills each time you open the inventory.
 */
public final class AutoRefill extends Module {
    private static final int SPLASH_HEALING_II = 16421;
    private static final long OPEN_SETTLE_MS = 75;
    private static final long AUTOMATIC_SCAN_MS = 500;
    private static final List<String> MOVEMENT = List.of("forward", "back", "left", "right", "jump");

    private final Setting trigger = addChoice("Trigger", "When a refill runs",
            List.of("Manual", "Automatic", "On Inventory Open"), 0);
    private final Setting mode = addChoice("Mode", "Packet clicks straight away, Legit glides the cursor to each item",
            List.of("Packet", "Legit"), 0).visibleWhen(() -> !trigger.is("Automatic"));
    private final Setting mouseMove = addNumber("Mouse Move (ms)", "How long each glide takes", 120, 40, 500, 0)
            .visibleWhen(() -> !trigger.is("Automatic") && mode.is("Legit"));
    private final Setting speed = addNumber("Speed (ms)", "Wait between moves", 80, 0, 200, 0);
    private final Setting refillSoup = addBoolean("Refill Soup", "Move soup into the hotbar", true);
    private final Setting refillPotions = addBoolean("Refill Potions", "Move Healing II splash potions", true);

    private record Move(int hotbar, int source) {}

    private enum State { OPENING, REFILLING, MOVING, CLOSING, RESTORE_SPRINT }

    private final Deque<Move> pending = new ArrayDeque<>();
    private final InventoryCursor cursor = new InventoryCursor();
    private Move moving;
    private State state;
    private long stateStart;
    private long lastMove;
    private boolean packetFlow;
    private boolean passive;
    private boolean movementPaused;
    private boolean wasSprinting;
    private boolean inventoryWasOpen;
    private long lastScan;

    public AutoRefill() {
        super("AutoRefill", "Refills the hotbar with soup and pots", Category.PLAYER, Subcategory.MEDIUM);
    }

    @Override
    public String suffix() {
        return trigger.is("Manual") ? mode.choiceName() : trigger.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        state = null;
        inventoryWasOpen = false;
        lastScan = 0;
        if (trigger.is("Manual") && mc.thePlayer != null && mc.currentScreen == null && scan()) {
            start(mode.is("Packet"), false);
        }
    }

    @Override
    public void onDisable(Context context) {
        if (state != null) {
            if (packetFlow) {
                mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(mc.thePlayer.inventoryContainer.windowId));
            }
            stop();
        }
    }

    @Subscribe
    public void onLoadWorld(LoadWorldEvent event) {
        restoreMovement();
        state = null;
        pending.clear();
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (state == null) {
            idle(context, now);
            return;
        }
        /* A silent run cannot share the screen with one the player opened. */
        if (packetFlow && !passive && mc.currentScreen != null || !packetFlow && !(mc.currentScreen instanceof GuiInventory)) {
            if (packetFlow) {
                mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(player.inventoryContainer.windowId));
            }
            finish(context);
            return;
        }
        long elapsed = now - stateStart;
        switch (state) {
            case OPENING -> {
                if (elapsed >= OPEN_SETTLE_MS) {
                    restoreMovement();
                    enter(State.REFILLING);
                }
            }
            case REFILLING -> {
                if (elapsed < speed.intValue() || now - lastMove < speed.intValue()) {
                    return;
                }
                Move move = pending.poll();
                if (move == null) {
                    enter(State.CLOSING);
                    return;
                }
                if (!packetFlow && mode.is("Legit") && cursor.begin(move.source(), mouseMove.intValue(), 0, 0, false)) {
                    moving = move;
                    enter(State.MOVING);
                    return;
                }
                Game.swapSlot(move.source(), move.hotbar());
                lastMove = now;
            }
            case CLOSING -> {
                if (elapsed < speed.intValue()) {
                    return;
                }
                if (passive) {
                    finish(context);
                } else if (packetFlow) {
                    mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(player.inventoryContainer.windowId));
                    /* The paused keys dropped the sprint; one tap of the sprint key picks it back up. */
                    if (wasSprinting) {
                        Game.setBinding("sprint", true);
                        enter(State.RESTORE_SPRINT);
                    } else {
                        finish(context);
                    }
                } else {
                    player.closeScreen();
                    finish(context);
                }
            }
            case RESTORE_SPRINT -> {
                if (elapsed >= 100) {
                    Game.setBinding("sprint", Game.bindingPhysicallyHeld("sprint"));
                    finish(context);
                }
            }
        }
    }

    /* The glide runs per frame, so the cursor moves smoothly rather than in tick-sized jumps. */
    @Override
    public void onRender(Context context) {
        if (state == State.MOVING && mc.currentScreen instanceof GuiInventory && cursor.step()) {
            Game.swapSlot(moving.source(), moving.hotbar());
            lastMove = System.currentTimeMillis();
            enter(State.REFILLING);
        }
    }

    private void idle(Context context, long now) {
        if (trigger.is("Manual")) {
            setEnabled(false, context);
        } else if (trigger.is("Automatic")) {
            if (mc.currentScreen == null && now - lastScan >= AUTOMATIC_SCAN_MS) {
                lastScan = now;
                if (!hotbarHasHealing() && scan()) {
                    start(true, false);
                }
            }
        } else {
            boolean open = mc.currentScreen instanceof GuiInventory;
            if (open && !inventoryWasOpen && scan()) {
                start(false, true);
            }
            inventoryWasOpen = open;
        }
    }

    private void start(boolean packet, boolean alreadyOpen) {
        EntityPlayerSP player = mc.thePlayer;
        packetFlow = packet;
        passive = alreadyOpen;
        wasSprinting = player.isSprinting();
        lastMove = 0;
        if (alreadyOpen) {
            enter(State.REFILLING);
        } else if (packet) {
            for (String key : MOVEMENT) {
                Game.setBinding(key, false);
            }
            Game.setBinding("sprint", false);
            movementPaused = true;
            mc.getNetHandler().addToSendQueue(
                    new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
            enter(State.OPENING);
        } else {
            mc.displayGuiScreen(new GuiInventory(player));
            enter(State.REFILLING);
        }
    }

    private boolean hotbarHasHealing() {
        for (int slot = 0; slot < 9; ++slot) {
            if (wanted(mc.thePlayer.inventory.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /* Empty slots take soup or a potion; a bowl's slot only ever takes soup. */
    private boolean scan() {
        pending.clear();
        List<Integer> soups = new ArrayList<>();
        List<Integer> potions = new ArrayList<>();
        for (int slot = 9; slot < 36; ++slot) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (refillSoup.bool() && isSoup(stack)) {
                soups.add(slot);
            } else if (refillPotions.bool() && isPotion(stack)) {
                potions.add(slot);
            }
        }
        int soup = 0;
        int potion = 0;
        for (int hotbar = 0; hotbar < 9; ++hotbar) {
            ItemStack existing = mc.thePlayer.inventory.getStackInSlot(hotbar);
            boolean empty = existing == null;
            boolean bowl = existing != null && existing.getItem() == Items.bowl;
            if (refillSoup.bool() && (empty || bowl) && soup < soups.size()) {
                pending.add(new Move(hotbar, soups.get(soup++)));
            } else if (refillPotions.bool() && empty && potion < potions.size()) {
                pending.add(new Move(hotbar, potions.get(potion++)));
            }
        }
        return !pending.isEmpty();
    }

    private boolean wanted(ItemStack stack) {
        return refillSoup.bool() && isSoup(stack) || refillPotions.bool() && isPotion(stack);
    }

    private static boolean isSoup(ItemStack stack) {
        return stack != null && stack.getItem() == Items.mushroom_stew;
    }

    private static boolean isPotion(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemPotion && stack.getItemDamage() == SPLASH_HEALING_II;
    }

    private void restoreMovement() {
        if (!movementPaused) {
            return;
        }
        for (String key : MOVEMENT) {
            Game.setBinding(key, Game.bindingPhysicallyHeld(key));
        }
        Game.setBinding("sprint", Game.bindingPhysicallyHeld("sprint"));
        movementPaused = false;
    }

    private void finish(Context context) {
        stop();
        lastScan = System.currentTimeMillis();
        if (trigger.is("Manual")) {
            setEnabled(false, context);
        }
    }

    private void stop() {
        restoreMovement();
        state = null;
        pending.clear();
    }

    private void enter(State next) {
        state = next;
        stateStart = System.currentTimeMillis();
    }
}
