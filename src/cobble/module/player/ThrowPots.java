package cobble.module.player;

import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;

/**
 * Cobble's ThrowPots, after Vape's Throwpot: one press uses the healing items
 * on the hotbar nearest first, drops the empty bowls, goes back to the slot it
 * started on and switches itself off. Dynamic stops before an item would
 * overheal; Single uses exactly one.
 */
public final class ThrowPots extends Module {
    private static final int SOUP_HEAL = 8;
    private static final int MAX_RETRIES = 2;
    private static final long PACKET_TIMEOUT_MS = 1000;
    /* Vape holds the use key this long; it is also how long the client takes to consume the stack. */
    private static final long USE_SETTLE_MS = 51;
    /* 1.8 potion metadata: the low nibble is the effect, 0x20 tier II, 0x4000 splash. */
    private static final int POTION_BASE = 0x0F;
    private static final int POTION_TIER_TWO = 0x20;
    private static final int POTION_REGENERATION = 1;
    private static final int POTION_HEALING = 5;

    private final Setting type = addChoice("Type", "Which healing items it uses", List.of("Both", "Pots", "Soup"), 0);
    private final Setting mode = addChoice("Mode", "Dynamic heals up to full, Single uses one item",
            List.of("Dynamic", "Single"), 0);
    private final Setting maxItems = addNumber("Max Items", "Most items one press uses", 9, 1, 9, 0)
            .visibleWhen(() -> mode.is("Dynamic"));
    private final Setting randomOrder = addBoolean("Random", "Scan the hotbar in a random order", false);
    private final Setting allowHealOne = addBoolean("Allow Heal I", "Also use tier I healing potions", true);
    private final Setting throwBowls = addBoolean("Throw Bowls", "Drop the bowl a soup leaves", true);
    private final Setting aim = addChoice("Aim", "Where potions are thrown",
            List.of("Current", "Down", "Silent Down"), 0);
    private final Setting delayMin = addNumber("Delay Min (ms)", "Shortest wait between items", 80, 0, 200, 0);
    private final Setting delayMax = addNumber("Delay Max (ms)", "Longest wait between items", 115, 0, 200, 0);
    private final Setting switchDelay = addNumber("Switch Delay (ms)", "Wait after changing slot", 50, 0, 250, 0);
    private final Setting scroll = addBoolean("Scroll", "Step through the hotbar instead of jumping", false);
    private final Setting scrollDelay = addNumber("Scroll Delay (ms)", "Wait between scroll steps", 100, 0, 200, 0)
            .visibleWhen(scroll::bool);
    private final Setting switchBack = addBoolean("Switch Back", "Return to the starting slot", true);
    private final Setting retryOnFail = addBoolean("Retry on Fail", "Try an item again if it was not used", true);

    private record Planned(int slot, Item item, int damage, int size, boolean soup, int heal) {}

    private enum State { SCROLL, SWITCH_DELAY, AWAIT_ROTATION, VERIFY, ITEM_DELAY, RESTORE_SCROLL, RESTORE_DELAY }

    private final SplittableRandom random = new SplittableRandom();
    private final List<Planned> plan = new ArrayList<>();
    private State state;
    private long stateStart;
    private int planIndex;
    private Planned target;
    private int scrollTarget;
    private int originalSlot;
    private float originalPitch;
    private boolean pitchModified;
    private boolean rotationRequested;
    private boolean rotationSent;
    private int retries;
    private long itemDelay;

    public ThrowPots() {
        super("ThrowPots", "Uses the healing items on your hotbar", Category.PLAYER, Subcategory.MEDIUM);
    }

    @Override
    public String suffix() {
        return mode.choiceName() + " " + type.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        state = null;
        plan.clear();
        planIndex = 0;
        retries = 0;
        pitchModified = false;
        rotationRequested = false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.currentScreen != null || player.getHealth() <= 0.0f) {
            return;
        }
        originalSlot = player.inventory.currentItem;
        originalPitch = player.rotationPitch;
        if (buildPlan()) {
            selectNextItem();
        }
    }

    @Override
    public void onDisable(Context context) {
        if (state != null) {
            restorePlayer();
            state = null;
        }
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (state == null || player == null || mc.currentScreen != null || player.getHealth() <= 0.0f) {
            setEnabled(false, context);
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - stateStart;
        switch (state) {
            case SCROLL, RESTORE_SCROLL -> {
                if (elapsed < scrollDelay.intValue()) {
                    return;
                }
                if (player.inventory.currentItem == scrollTarget) {
                    if (state == State.SCROLL) {
                        enter(State.SWITCH_DELAY);
                    } else {
                        finish(context);
                    }
                    return;
                }
                int current = player.inventory.currentItem;
                Game.setSelectedSlot(current < scrollTarget ? current + 1 : current - 1);
                stateStart = now;
            }
            case SWITCH_DELAY -> {
                if (elapsed < switchDelay.intValue()) {
                    return;
                }
                if (player.inventory.currentItem != target.slot() || !targetPresent()) {
                    retryOrRestore();
                } else if (aim.is("Current")) {
                    use();
                } else {
                    requestDownRotation();
                }
            }
            case AWAIT_ROTATION -> {
                if (rotationSent) {
                    use();
                } else if (elapsed >= PACKET_TIMEOUT_MS) {
                    retryOrRestore();
                }
            }
            case VERIFY -> {
                if (elapsed < USE_SETTLE_MS) {
                    return;
                }
                if (targetPresent()) {
                    retryOrRestore();
                    return;
                }
                if (target.soup() && throwBowls.bool()) {
                    dropBowl(target.slot());
                }
                ++planIndex;
                retries = 0;
                itemDelay = rollItemDelay();
                enter(State.ITEM_DELAY);
            }
            case ITEM_DELAY -> {
                if (elapsed >= itemDelay && !selectNextItem()) {
                    beginRestore();
                }
            }
            case RESTORE_DELAY -> {
                if (elapsed >= switchDelay.intValue()) {
                    finish(context);
                }
            }
        }
    }

    /* The use goes out at the end of the tick, after the movement packet that
     * carried the downward pitch, so the server throws it at that pitch. */
    @Subscribe
    public void onWalkingUpdate(UpdateWalkingPlayerEvent event) {
        if (rotationRequested) {
            event.setPitch(90.0f);
            rotationSent = true;
        }
    }

    private boolean buildPlan() {
        EntityPlayerSP player = mc.thePlayer;
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
        if (randomOrder.bool()) {
            for (int i = order.size() - 1; i > 0; --i) {
                Collections.swap(order, i, random.nextInt(i + 1));
            }
        }
        boolean soupAllowed = !type.is("Pots");
        boolean potsAllowed = !type.is("Soup");
        float flooredHealth = (float) Math.floor(player.getHealth());
        int queuedHealing = 0;
        for (int slot : order) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            int damage = stack.getItemDamage();
            int heal;
            boolean soup = false;
            if (soupAllowed && stack.getItem() == Items.mushroom_stew) {
                soup = true;
                heal = SOUP_HEAL;
            } else if (potsAllowed && stack.getItem() instanceof ItemPotion && ItemPotion.isSplash(damage)
                    && healing(damage)) {
                boolean strong = (damage & POTION_TIER_TWO) != 0;
                if (!strong && !allowHealOne.bool()) {
                    continue;
                }
                /* Vape's 4 * (amplifier + 1). */
                heal = strong ? 8 : 4;
            } else {
                continue;
            }
            Planned item = new Planned(slot, stack.getItem(), damage, stack.stackSize, soup, heal);
            if (mode.is("Single")) {
                plan.add(item);
                return true;
            }
            if (queuedHealing + item.heal() + flooredHealth > player.getMaxHealth()) {
                continue;
            }
            queuedHealing += item.heal();
            plan.add(item);
            if (plan.size() >= maxItems.intValue()) {
                break;
            }
        }
        plan.sort(Comparator.comparingInt(item -> Math.abs(item.slot() - originalSlot)));
        return !plan.isEmpty();
    }

    /* Vape counts Instant Health and Regeneration as healing. */
    private static boolean healing(int damage) {
        int base = damage & POTION_BASE;
        return base == POTION_HEALING || base == POTION_REGENERATION;
    }

    /* An item that went missing mid-run, used up or moved, is skipped rather than ending the run. */
    private boolean selectNextItem() {
        while (planIndex < plan.size()) {
            target = plan.get(planIndex);
            if (targetPresent()) {
                rotationRequested = false;
                rotationSent = false;
                beginSlotChange(target.slot(), State.SWITCH_DELAY);
                return true;
            }
            ++planIndex;
        }
        return false;
    }

    private boolean targetPresent() {
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(target.slot());
        return stack != null && stack.getItem() == target.item() && stack.getItemDamage() == target.damage()
                && stack.stackSize >= target.size();
    }

    private void beginSlotChange(int slot, State arrival) {
        scrollTarget = slot;
        if (scroll.bool()) {
            enter(arrival == State.SWITCH_DELAY ? State.SCROLL : State.RESTORE_SCROLL);
            return;
        }
        Game.setSelectedSlot(slot);
        enter(arrival);
    }

    private void requestDownRotation() {
        rotationSent = false;
        rotationRequested = true;
        if (aim.is("Down")) {
            mc.thePlayer.rotationPitch = 90.0f;
            mc.thePlayer.prevRotationPitch = 90.0f;
            pitchModified = true;
        }
        enter(State.AWAIT_ROTATION);
    }

    /* sendUseItem reports whether the local stack changed, which soup does not
     * (the bowl replaces it in place), so success is judged in VERIFY instead. */
    private void use() {
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
        rotationRequested = false;
        enter(State.VERIFY);
    }

    private void dropBowl(int slot) {
        mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId, 36 + slot, 0, 4, mc.thePlayer);
    }

    private void retryOrRestore() {
        rotationRequested = false;
        if (retryOnFail.bool() && retries < MAX_RETRIES && targetPresent()) {
            ++retries;
            beginSlotChange(target.slot(), State.SWITCH_DELAY);
            return;
        }
        beginRestore();
    }

    /* Without a slot to go back to it is done; the next tick switches it off. */
    private void beginRestore() {
        if (!switchBack.bool()) {
            restorePlayer();
            state = null;
            return;
        }
        rotationRequested = false;
        beginSlotChange(originalSlot, State.RESTORE_DELAY);
    }

    private void finish(Context context) {
        restorePlayer();
        state = null;
        setEnabled(false, context);
    }

    private void restorePlayer() {
        rotationRequested = false;
        if (mc.thePlayer == null) {
            return;
        }
        if (pitchModified) {
            mc.thePlayer.rotationPitch = originalPitch;
            mc.thePlayer.prevRotationPitch = originalPitch;
            pitchModified = false;
        }
        if (switchBack.bool()) {
            Game.setSelectedSlot(originalSlot);
        }
    }

    private long rollItemDelay() {
        int low = Math.min(delayMin.intValue(), delayMax.intValue());
        int high = Math.max(delayMin.intValue(), delayMax.intValue());
        return low + random.nextInt(high - low + 1);
    }

    private void enter(State next) {
        state = next;
        stateStart = System.currentTimeMillis();
    }
}
