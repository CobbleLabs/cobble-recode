package cobble.module.combat;

import cobble.event.LeftClickEvent;
import cobble.event.LoadWorldEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.ui.Client;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

/**
 * Cobble's BlockHit: the sword goes up while a hit can land and comes down on
 * the beat of the clicks, a few swings at a time, for the sprint resets.
 *
 * <p>It pays for the block the way vanilla does. On the tick it raises the
 * sword it applies vanilla's use-item slowdown itself, because vanilla's
 * branch already ran with no item in use; from the next tick vanilla charges
 * it on its own. On the release tick it gives that tick's slowdown back,
 * because a hand's release goes out before the slowdown runs. Both happen from
 * the hook at the end of EntityPlayerSP.onLivingUpdate, where vanilla has
 * scaled the input and has not yet moved with it.
 */
public final class BlockHit extends LegacyModule {
    /* How long a hit taken keeps "someone is on us" true: 2 s, about one exchange. */
    private static final int PRESSURE_TICKS = 40;
    private static final int FAKE_USE_COUNT = 72000;

    private final Setting range = addNumber("Range", "Distance at which a fight counts as live", 3.4f, 1, 6, 1);
    private final Setting maxHurtTime = addNumber("Maximum Hurt Time", "Stand down while hurt longer than this",
            4, 0, 10, 0);
    private final Setting holdDuration = addNumber("Hold Duration", "Longest a block stays up, in ticks",
            3.5f, 1, 10, 1);
    private final Setting requireLeft = addBoolean("Require Left Click", "Only while attacking", true);
    private final Setting requireRight = addBoolean("Require Right Click", "Only while holding right click", false);
    private final Setting requireDamage = addBoolean("Require Damage", "Only after taking a hit", false);
    private final Setting fakeAnimation = addBoolean("Fake Animation", "Show the block between real ones", false);
    private final Setting fakeInRange = addBoolean("Only Within Range", "Fake it only during a live fight", true)
            .visibleWhen(fakeAnimation::bool);
    private final Setting lagChance = addNumber("Lag Chance", "Chance to pause after a release", 0, 0, 100, 0);
    private final Setting lagDuration = addNumber("Lag Duration", "Ticks the pause lasts", 2.5f, 0.5f, 10, 1);
    private final Setting lagFlushAttack = addBoolean("Flush On Attack", "A swing ends the pause", false);
    private final Setting reblockInstantly = addBoolean("Reblock Instantly", "No gap between blocks", false);

    private final SplittableRandom random = new SplittableRandom();
    private int tick;
    private int blockedAt;
    private int releasedAt;
    private int lastHitTaken;
    private boolean haveTakenHit;
    private int previousHurt;
    private int beats;
    private int beatsHeld;
    private boolean holePending;
    private boolean immuneHole;
    private boolean wasSprinting;
    private boolean serverBlocking;
    private boolean fakingBlock;
    private int lagTicksLeft;
    private String gate;

    /* The pattern, drawn fresh after every release. */
    private int beatsPerHole;
    private int minHoldTicks;
    private int maxHoldTicks;
    private int holeTicks;
    private int immuneMargin;

    public BlockHit() {
        super("BlockHit", "Blocks between hits on the beat of your clicks", Category.COMBAT, Subcategory.MEDIUM);
        rollCycle();
    }

    @Override
    public String suffix() {
        if (fakingBlock) {
            return "Fake";
        }
        if (gate != null) {
            return gate;
        }
        return serverBlocking ? "Block" : "Open";
    }

    /* The block itself is not dropped here: the next hook call sees the module
     * off and lowers it, from the game thread at the point it belongs. */
    @Override
    public void onEnable(Context context) {
        tick = 0;
        blockedAt = 0;
        releasedAt = 0;
        lastHitTaken = 0;
        haveTakenHit = false;
        previousHurt = 0;
        beats = 0;
        beatsHeld = 0;
        holePending = false;
        immuneHole = false;
        wasSprinting = false;
        gate = null;
        rollCycle();
    }

    /* The server player that held the block went with the world. */
    @Subscribe
    public void onLoadWorld(LoadWorldEvent event) {
        serverBlocking = false;
        fakingBlock = false;
        haveTakenHit = false;
        previousHurt = 0;
        beatsHeld = 0;
        holePending = false;
        lagTicksLeft = 0;
    }

    @Subscribe
    public void onLeftClick(LeftClickEvent event) {
        ++beats;
    }

    private void rollCycle() {
        int pick = random.nextInt(100);
        beatsPerHole = pick < 22 ? 3 : pick < 58 ? 4 : pick < 84 ? 5 : 6;
        minHoldTicks = 2 + random.nextInt(3);
        /* A fractional hold rounds up that share of the time. */
        int base = (int) holdDuration.number();
        float fraction = holdDuration.number() - base;
        maxHoldTicks = Math.max(1, base + (random.nextInt(100) < (int) (fraction * 100.0f) ? 1 : 0));
        minHoldTicks = Math.min(minHoldTicks, maxHoldTicks);
        if (reblockInstantly.bool()) {
            holeTicks = 0;
        } else {
            holeTicks = random.nextInt(100) < 86 ? 4 + random.nextInt(5) : 9 + random.nextInt(6);
        }
        immuneMargin = 2 + random.nextInt(4);
    }

    /** Called every tick from the living update hook, on or off: an outstanding block is ours to lower. */
    public void livingUpdate(EntityPlayerSP player) {
        if (!enabled() && !serverBlocking && !fakingBlock) {
            return;
        }
        ++tick;
        /* One tick had a swing or it did not; a manual click and an AutoClicker click together are one beat. */
        boolean beat = beats > 0;
        beats = 0;
        /* hurtTime jumps to 10 on every hit that lands, so a rise is one incoming hit. */
        int hurt = player.hurtTime;
        boolean hitTaken = hurt > previousHurt;
        if (hitTaken) {
            lastHitTaken = tick;
            haveTakenHit = true;
            beat = true;
        }
        previousHurt = hurt;

        boolean rightDown = Mouse.isButtonDown(1);
        boolean leftDown = Mouse.isButtonDown(0) || ModuleManager.get(AutoClicker.class).enabled();
        /* Holding right click is a condition here, not a block of its own, so vanilla must not fight the cycle. */
        if (requireRight.bool() && rightDown && Game.get().holdingSword()) {
            Game.get().setBinding("use", false);
        }
        /* Use-item state this module did not create belongs to someone else. */
        boolean foreignUse = player.isUsingItem() && !serverBlocking;
        boolean manualBlock = !requireRight.bool() && rightDown || foreignUse;

        boolean handOver = false;
        if (!enabled()) {
            gate = "Off";
        } else if (mc.currentScreen != null || Client.gui().visible()) {
            gate = "GUI";
        } else if (requireLeft.bool() && !leftDown) {
            gate = "No LMB";
        } else if (requireRight.bool() && !rightDown) {
            gate = "No RMB";
        } else if (manualBlock) {
            gate = "Manual";
            handOver = foreignUse;
        } else if (player.ridingEntity != null) {
            /* Vanilla skips the slowdown on a mount, so a block there would be free. */
            gate = "Riding";
        } else if (!Game.get().holdingSword()) {
            /* The server tests the stack it started using, so a hotbar switch would keep a free block. */
            gate = "No Sword";
        } else {
            gate = null;
        }
        if (gate != null) {
            stopFaking(player);
            if (handOver) {
                /* Their right click already sent its own use; releasing would cancel it. */
                serverBlocking = false;
                beatsHeld = 0;
                holePending = false;
            } else {
                standDown(player);
            }
            return;
        }

        boolean live = fightLive(player, hurt);
        if (fakeAnimation.bool() && !serverBlocking && (!fakeInRange.bool() || live)) {
            player.itemInUseCount = FAKE_USE_COUNT;
            fakingBlock = true;
        } else if (!serverBlocking) {
            stopFaking(player);
        }

        if (lagTicksLeft > 0) {
            if (lagFlushAttack.bool() && beat) {
                lagTicksLeft = 0;
            } else {
                --lagTicksLeft;
                return;
            }
        }

        if (serverBlocking) {
            if (beat) {
                ++beatsHeld;
            }
            if (!live) {
                standDown(player);
                return;
            }
            int held = tick - blockedAt;
            /* The floor keeps a double signal on one tick from sending a use and a release back to back. */
            if (held >= minHoldTicks && (hitTaken || beatsHeld >= beatsPerHole || held >= maxHoldTicks)) {
                sendRelease(player, hurt > 0);
            }
            return;
        }

        if (!live) {
            /* A hole belongs to a live exchange; the next fight opens on a swing. */
            holePending = false;
            return;
        }
        if (holePending) {
            /* A hole a hit opened lasts as long as the immunity that made it free;
             * otherwise it runs on its own clock, long enough to be a sprint reset. */
            if (immuneHole ? hurt > immuneMargin : tick - releasedAt < holeTicks) {
                return;
            }
        } else if (!beat || hurt > immuneMargin) {
            /* Nothing swung yet, or we are deep in our own immunity and the next beat lands closer. */
            return;
        }
        sendBlock(player);
    }

    private boolean fightLive(EntityPlayerSP player, int hurt) {
        boolean pressured = haveTakenHit && tick - lastHitTaken <= PRESSURE_TICKS;
        if (requireDamage.bool() && hurt <= 0 && !pressured || hurt > maxHurtTime.intValue()) {
            return false;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                || ModuleManager.get(AimAssist.class).hasTarget() || pressured) {
            return true;
        }
        double reach = range.number();
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other != player && !AntiBot.shouldHideEntity(other) && player.getDistanceSqToEntity(other) <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    private void sendBlock(EntityPlayerSP player) {
        /* Read before the use: the slowdown below drops the sprint, and the release puts it back. */
        wasSprinting = player.isSprinting();
        /* A sword hands the same stack back, so the reported change is false on every block;
         * the client entering use-item state is what says the packet went out. */
        boolean took = mc.playerController.sendUseItem(player, mc.theWorld, player.getHeldItem())
                || player.isUsingItem();
        if (!took) {
            wasSprinting = false;
            return;
        }
        fakingBlock = false;
        serverBlocking = true;
        blockedAt = tick;
        beatsHeld = 0;
        holePending = false;
        immuneHole = false;
        MovementInput input = player.movementInput;
        input.moveStrafe *= 0.2f;
        input.moveForward *= 0.2f;
        player.sprintToggleTimer = 0;
        if (input.moveForward < 0.8f && player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    private void sendRelease(EntityPlayerSP player, boolean immune) {
        serverBlocking = false;
        releasedAt = tick;
        beatsHeld = 0;
        holePending = true;
        immuneHole = immune;
        rollCycle();
        if (lagChance.intValue() > 0 && random.nextInt(100) < lagChance.intValue()) {
            lagTicksLeft = (int) Math.max(1.0f, lagDuration.number());
        }
        /* Vanilla only scaled this tick's input if an item was in use off a mount; giving back a scale
         * that never happened would be a five-fold input. */
        boolean scaled = player.isUsingItem() && player.ridingEntity == null;
        mc.playerController.onStoppedUsingItem(player);
        MovementInput input = player.movementInput;
        if (scaled) {
            input.moveStrafe *= 5.0f;
            input.moveForward *= 5.0f;
        }
        /* The slowdown zeroed the double-tap timer, so only real forward input brings the sprint back. */
        if (wasSprinting && input.moveForward >= 0.8f && !player.isSprinting()) {
            player.setSprinting(true);
        }
        wasSprinting = false;
    }

    private void standDown(EntityPlayerSP player) {
        if (serverBlocking) {
            sendRelease(player, false);
        }
        beatsHeld = 0;
        holePending = false;
        lagTicksLeft = 0;
    }

    private void stopFaking(EntityPlayerSP player) {
        if (fakingBlock) {
            player.itemInUseCount = 0;
            fakingBlock = false;
        }
    }
}
