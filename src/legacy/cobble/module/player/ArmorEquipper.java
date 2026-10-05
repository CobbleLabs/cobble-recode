package cobble.module.player;

import cobble.event.EventBus;
import cobble.event.RotationEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.util.InventoryCursor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;

/**
 * Puts armor on through number-key clicks on the armor slots, for ArmorSwitch
 * and AutoArmor. A piece already on the hotbar is equipped straight from
 * there; one in the inventory is brought to the hotbar, equipped, and the old
 * piece put back where it came from. A run shows the inventory, glides the
 * cursor over it, or keeps it hidden, as the settings it adds to its module say.
 */
final class ArmorEquipper {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long OPEN_TIMEOUT_MS = 1000;
    private static final long VERIFY_DELAY_MS = 40;
    private static final int RETRY_MIN_DELAY = 70;
    private static final int MAX_RETRIES = 5;

    private final Setting delay;
    private final Setting retryOnFail;
    private final Setting silent;
    private final Setting legit;
    private final Setting mouseMove;

    /* What each click should leave behind, for the retry check. */
    private enum Check { HOTBAR, ARMOR, INVENTORY }

    private record Swap(int containerSlot, int hotbar, Item expected, Check check, int slotType, int retries) {
        Swap retried() {
            return new Swap(containerSlot, hotbar, expected, check, slotType, retries + 1);
        }
    }

    private enum State { OPENING, SWITCHING, VISUAL_MOVE, VERIFYING, CLOSING }

    private final Deque<Swap> pending = new ArrayDeque<>();
    private final InventoryCursor cursor = new InventoryCursor();
    private final SplittableRandom random = new SplittableRandom();
    private State state;
    private long stateStart;
    private long lastClick;
    private Swap lastSwap;
    private boolean passive;

    ArmorEquipper(Module module) {
        delay = module.addNumber("Delay (ms)", "Wait between swaps", 50, 0, 200, 0);
        retryOnFail = module.addBoolean("Retry on Fail", "Check each swap and redo it on slow servers", false);
        silent = module.addBoolean("Silent Mode", "Swap without showing the inventory", false);
        legit = module.addBoolean("Legit Mode", "Glide the cursor onto each slot before its swap", false)
                .visibleWhen(() -> !silent.bool());
        mouseMove = module.addNumber("Mouse Move (ms)", "How long each glide takes", 120, 80, 350, 0)
                .visibleWhen(() -> legit.bool() && !silent.bool());
        EventBus.register(this);
    }

    /*
     * From a silent run's open packet to its close the server takes the
     * inventory as open, and a player with the inventory open cannot turn, so
     * the look it last got is held. The silent rotation holds it and corrects
     * the movement for it; each tick's is settled before the modules run, which
     * is why the open packet waits a tick after the start. The movement packet
     * holds it too, against a module that turns the camera later in the tick.
     */
    private boolean holdingLook() {
        return state != null && silent.bool() && !passive;
    }

    @Subscribe(priority = Subscribe.Priority.LOWEST)
    private void onRotation(RotationEvent event) {
        if (holdingLook()) {
            event.setRotation(mc.thePlayer.lastReportedYaw, mc.thePlayer.lastReportedPitch,
                    RotationEvent.PRIORITY_MAXIMUM);
        }
    }

    @Subscribe(priority = Subscribe.Priority.LOWEST)
    private void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        if (holdingLook()) {
            event.setYaw(mc.thePlayer.lastReportedYaw);
            event.setPitch(mc.thePlayer.lastReportedPitch);
        }
    }

    boolean running() {
        return state != null;
    }

    void stop() {
        state = null;
        pending.clear();
    }

    /**
     * Starts putting on, for each armor type, the piece in the inventory slot
     * {@code sources} holds for it, or nothing where that is -1; false when that
     * leaves nothing to do. A passive run works in the inventory the player has
     * open and leaves it open.
     */
    boolean start(int[] sources, boolean passive) {
        if (!plan(sources)) {
            return false;
        }
        this.passive = passive;
        lastClick = 0;
        if (passive) {
            enter(State.SWITCHING);
        } else {
            if (!silent.bool()) {
                mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
            }
            enter(State.OPENING);
        }
        return true;
    }

    /** Advances a running run; false once it is over. */
    boolean tick() {
        EntityPlayerSP player = mc.thePlayer;
        long now = System.currentTimeMillis();
        long elapsed = now - stateStart;
        boolean screenGone = !silent.bool() && !(mc.currentScreen instanceof GuiInventory);
        switch (state) {
            case OPENING -> {
                if (silent.bool()) {
                    mc.getNetHandler().addToSendQueue(
                            new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
                    enter(State.SWITCHING);
                } else if (mc.currentScreen instanceof GuiInventory) {
                    enter(State.SWITCHING);
                } else if (elapsed > OPEN_TIMEOUT_MS) {
                    stop();
                }
            }
            case SWITCHING -> {
                if (screenGone && !passive || passive && !(mc.currentScreen instanceof GuiInventory)) {
                    stop();
                } else if (pending.isEmpty()) {
                    enter(State.CLOSING);
                } else if (now - lastClick >= effectiveDelay()) {
                    lastSwap = pending.poll();
                    /* Each glide differs a little in length and landing point, as a hand's would. */
                    if (legit.bool() && !silent.bool() && cursor.begin(lastSwap.containerSlot(),
                            Math.max(80, mouseMove.intValue() + random.nextInt(-25, 36)), random.nextInt(-3, 4),
                            random.nextInt(-3, 4), true)) {
                        enter(State.VISUAL_MOVE);
                    } else {
                        swapNow();
                        if (retryOnFail.bool()) {
                            enter(State.VERIFYING);
                        }
                    }
                }
            }
            case VISUAL_MOVE -> {
                if (screenGone) {
                    stop();
                }
            }
            case VERIFYING -> {
                if (elapsed >= Math.max(VERIFY_DELAY_MS, effectiveDelay())) {
                    if (!landed(lastSwap) && lastSwap.retries() < MAX_RETRIES) {
                        pending.addFirst(lastSwap.retried());
                        lastClick = 0;
                    }
                    enter(State.SWITCHING);
                }
            }
            case CLOSING -> {
                if (silent.bool() && !passive) {
                    mc.getNetHandler().addToSendQueue(new C0DPacketCloseWindow(player.inventoryContainer.windowId));
                    stop();
                } else if (elapsed >= delay.intValue()) {
                    if (!passive && mc.currentScreen instanceof GuiInventory) {
                        player.closeScreen();
                    }
                    stop();
                }
            }
        }
        return state != null;
    }

    /* The glide runs per frame, so the cursor moves smoothly rather than in tick-sized jumps. */
    void render() {
        if (state == State.VISUAL_MOVE && mc.currentScreen instanceof GuiInventory && cursor.step()) {
            swapNow();
            enter(State.VERIFYING);
        }
    }

    private void swapNow() {
        mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId, lastSwap.containerSlot(),
                lastSwap.hotbar(), 2, mc.thePlayer);
        lastClick = System.currentTimeMillis();
    }

    private int effectiveDelay() {
        return retryOnFail.bool() ? Math.max(delay.intValue(), RETRY_MIN_DELAY) : delay.intValue();
    }

    private boolean landed(Swap swap) {
        EntityPlayerSP player = mc.thePlayer;
        ItemStack stack = switch (swap.check()) {
            case HOTBAR -> player.inventory.getStackInSlot(swap.hotbar());
            case ARMOR -> player.inventory.armorItemInSlot(3 - swap.slotType());
            case INVENTORY -> player.inventory.getStackInSlot(swap.containerSlot());
        };
        return stack != null && stack.getItem() == swap.expected();
    }

    private void enter(State next) {
        state = next;
        stateStart = System.currentTimeMillis();
    }

    private boolean plan(int[] sources) {
        pending.clear();
        EntityPlayerSP player = mc.thePlayer;
        /* A piece on the hotbar keeps its slot, so no other piece is routed through it. */
        boolean[] reserved = new boolean[9];
        for (int source : sources) {
            if (source >= 0 && source < 9) {
                reserved[source] = true;
            }
        }
        record Piece(int type, Item item, int source, int hotbar, Item old, Item displaced) {}
        List<Piece> pieces = new ArrayList<>();
        for (int type = 0; type < 4; ++type) {
            int source = sources[type];
            if (source < 0) {
                continue;
            }
            int hotbar = source < 9 ? source : freeHotbar(reserved);
            if (hotbar < 0) {
                continue;
            }
            reserved[hotbar] = true;
            ItemStack current = player.inventory.armorItemInSlot(3 - type);
            ItemStack occupant = source < 9 ? null : player.inventory.getStackInSlot(hotbar);
            pieces.add(new Piece(type, player.inventory.getStackInSlot(source).getItem(), source, hotbar,
                    current == null ? null : current.getItem(), occupant == null ? null : occupant.getItem()));
        }
        /* Bring everything to the hotbar first, then equip, then put the old pieces where the new ones were. */
        for (Piece piece : pieces) {
            if (piece.source() >= 9) {
                pending.add(new Swap(piece.source(), piece.hotbar(), piece.item(), Check.HOTBAR, piece.type(), 0));
            }
        }
        for (Piece piece : pieces) {
            pending.add(new Swap(5 + piece.type(), piece.hotbar(), piece.item(), Check.ARMOR, piece.type(), 0));
        }
        for (Piece piece : pieces) {
            if (piece.source() < 9) {
                continue;
            }
            if (piece.old() != null) {
                pending.add(new Swap(piece.source(), piece.hotbar(), piece.old(), Check.INVENTORY, piece.type(), 0));
            } else if (piece.displaced() != null) {
                /* Nothing came off, so the swap back only returns what the hotbar slot held. */
                pending.add(new Swap(piece.source(), piece.hotbar(), piece.displaced(), Check.HOTBAR, piece.type(),
                        0));
            }
        }
        return !pending.isEmpty();
    }

    /* An empty slot, or failing that one of the middle four. */
    private int freeHotbar(boolean[] reserved) {
        for (int slot = 0; slot < 9; ++slot) {
            if (!reserved[slot] && mc.thePlayer.inventory.getStackInSlot(slot) == null) {
                return slot;
            }
        }
        for (int slot = 2; slot <= 5; ++slot) {
            if (!reserved[slot]) {
                return slot;
            }
        }
        return -1;
    }
}
