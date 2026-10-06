package cobble.module.combat;

import cobble.game.CrosshairTarget;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.combat.autoclicker.ClickRhythm;
import cobble.ui.Client;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Clicks while the left button is held. The gaps come from {@link ClickRhythm}
 * and each press is a real press and release in the game's mouse queue, so the
 * game and Lunar's CPS counter read them as they read the physical button, and
 * keystroke displays show each one rather than the held button.
 *
 * <p>Events are due at times laid out from the previous event's due time, not
 * from the frame that sent it, so frame rate shifts a click by at most a frame
 * and never slows the rate down.
 */
public class AutoClicker extends Module {
    private static final int LEFT = 0;
    private static final double INVENTORY_INTERVAL_MS = 1000.0 / 16.0;
    /* A stalled frame would otherwise pay back every missed click at once. */
    private static final double STALL_MS = 250.0;
    private static final double STABLE_HOLD_SHARE = 0.4;

    /* Stable CPS swaps the range and its randomization for one rate, in the same place in the panel. */
    private final Setting stableCps = addNumber("CPS", "Fixed clicks per second", 17, 1, 25, 0)
            .visibleWhen(() -> this.stable.bool());
    private final Setting minCps = addNumber("Min CPS", "Lowest clicks per second", 14, 1, 20, 1)
            .visibleWhen(() -> !this.stable.bool());
    private final Setting maxCps = addNumber("Max CPS", "Highest clicks per second", 16, 1, 20, 1)
            .visibleWhen(() -> !this.stable.bool());
    private final Setting randomization = addNumber("Randomization (%)", "How much each gap varies around the rate",
            100, 0, 100, 0).visibleWhen(() -> !this.stable.bool());
    private final Setting stable = addBoolean("Stable CPS", "Click at one fixed rate", false);
    private final Setting weaponOnly = addBoolean("Weapon Only", "Only click holding a sword or an axe", false);
    private final Setting breakBlocks = addBoolean("Break Blocks", "Stop clicking while looking at a block", false);
    private final Setting inventory = addBoolean("Inventory", "Shift and hold left in a container to quick-move", true);

    private final ClickRhythm rhythm = new ClickRhythm(new SplittableRandom());
    private double nextEventAt = -1;
    private double nextPressAt;
    private boolean down;
    private boolean breaking;
    private double nextInventoryClickAt = -1;
    /* Press times over the last second, for the readout next to the name. */
    private final double[] recentPresses = new double[32];
    private int recentIndex;

    public AutoClicker() {
        super("AutoClicker", "Clicks for you while you hold left", Category.COMBAT, Subcategory.BASIC);
        Arrays.fill(recentPresses, Double.NEGATIVE_INFINITY);
    }

    @Override
    public String suffix() {
        double now = System.nanoTime() / 1.0e6;
        int clicks = 0;
        for (double pressedAt : recentPresses) {
            if (now - pressedAt < 1000.0) {
                clicks++;
            }
        }
        if (clicks > 0) {
            return clicks + "cps";
        }
        if (stable.bool()) {
            return stableCps.intValue() + "cps";
        }
        return Math.round(Math.min(minCps.number(), maxCps.number())) + "-"
                + Math.round(Math.max(minCps.number(), maxCps.number())) + "cps";
    }

    @Override
    public void onEnable(Context context) {
        nextEventAt = -1;
        down = false;
        breaking = false;
        nextInventoryClickAt = -1;
    }

    @Override
    public void onDisable(Context context) {
        release();
    }

    /* Per frame rather than per tick, so a press is in the queue the moment it is due. */
    @Override
    public void onRender(Context context) {
        FrameState state = context.state;
        Game game = Game.get();
        if (!state.inWorld || !state.player.valid || state.player.health <= 0.0f || Client.gui().visible()
                || !game.inGameFocused() || !Input.attackHeld()
                || weaponOnly.bool() && !game.holdingWeapon()) {
            release();
            return;
        }
        game.showQueuedMouseButton(LEFT, true);
        /* Digging needs the button held, so one press goes in and stays down until the block is left. */
        if (breakBlocks.bool() && targetingBlock()) {
            if (!breaking) {
                if (down) {
                    queue(false);
                }
                queue(true);
                breaking = true;
            }
            return;
        }
        double now = System.nanoTime() / 1.0e6;
        if (breaking) {
            queue(false);
            breaking = false;
            scheduleFrom(now);
            nextEventAt = nextPressAt;
        }

        /* The physical press is the first click: it is let go after a hold of
         * its own and the clicking carries on from it. */
        if (nextEventAt < 0) {
            rhythm.reset();
            down = true;
            scheduleFrom(now);
            return;
        }
        if (now - nextEventAt > STALL_MS) {
            nextEventAt = now;
            nextPressAt = Math.max(nextPressAt, now);
        }
        while (now >= nextEventAt) {
            if (down) {
                queue(false);
                nextEventAt = nextPressAt;
            } else {
                queue(true);
                recentPresses[recentIndex] = now;
                recentIndex = (recentIndex + 1) % recentPresses.length;
                scheduleFrom(nextEventAt);
                /* Let go on a later frame, so the frame in between draws it down. */
                break;
            }
        }
    }

    /* Lays out the release and the next press from when this press was due. */
    private void scheduleFrom(double pressedAt) {
        double interval;
        double hold;
        if (stable.bool()) {
            interval = 1000.0 / stableCps.number();
            hold = interval * STABLE_HOLD_SHARE;
        } else {
            double amount = randomization.number() / 100.0;
            interval = rhythm.nextInterval(minCps.number(), maxCps.number(), amount);
            hold = rhythm.holdFor(interval, amount);
        }
        nextPressAt = pressedAt + interval;
        nextEventAt = pressedAt + hold;
    }

    /* A miss holds the next attack back ten ticks; a press of ours is not held back by one of its own. */
    private void queue(boolean pressed) {
        if (pressed) {
            Game.get().setAttackCooldown(0);
        }
        Game.get().queueMouseButton(LEFT, pressed);
        down = pressed;
    }

    /* Whatever of ours is still down comes back up, so the game is never left holding a press we made. */
    private void release() {
        Game game = Game.get();
        if (down || breaking) {
            game.queueMouseButton(LEFT, false);
        }
        game.showQueuedMouseButton(LEFT, false);
        down = false;
        breaking = false;
        nextEventAt = -1;
    }

    @Override
    public void onTick(Context context) {
        int slot = inventoryClickActive(context.state) ? Game.get().quickMoveSlot() : -1;
        if (slot < 0) {
            nextInventoryClickAt = -1;
            return;
        }
        double now = System.nanoTime() / 1.0e6;
        if (nextInventoryClickAt < 0) {
            nextInventoryClickAt = now + INVENTORY_INTERVAL_MS;
            return;
        }
        if (nextInventoryClickAt > now) {
            return;
        }
        nextInventoryClickAt = Math.max(nextInventoryClickAt + INVENTORY_INTERVAL_MS, now);
        Game.get().quickMove(slot);
    }

    private boolean inventoryClickActive(FrameState state) {
        return inventory.bool() && state.inWorld && !Client.gui().visible()
                && (Input.modifiers() & Input.MOD_SHIFT) != 0 && Input.attackHeld()
                && !ModuleManager.inventoryBusy();
    }

    private static boolean targetingBlock() {
        return Game.get().crosshair().kind() == CrosshairTarget.Kind.BLOCK;
    }
}
