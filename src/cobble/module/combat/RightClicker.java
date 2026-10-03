package cobble.module.combat;

import cobble.event.GameLoopEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.combat.rightclicker.ClickTiming;
import cobble.ui.Client;
import cobble.util.InputHookManager;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Mouse;

/**
 * Clicks the right button for you while you hold it. Each click is a real
 * press and release in the game's mouse queue, the press held for a share of
 * the gap that shrinks as the rate climbs, the way a finger that clicks faster
 * also lets go sooner. Jitter shakes the aim a few mouse counts with each click.
 */
public final class RightClicker extends Module {
    private static final int RIGHT = 1;
    /* The first click waits this long after the button goes down, on top of the start delay. */
    private static final long ACTIVATION_MS = 50;
    private static final float JITTER_COUNTS = 7.0f;

    private final Setting minCps = addNumber("Min CPS", "Lowest clicks per second", 7, 1, 20, 0);
    private final Setting maxCps = addNumber("Max CPS", "Highest clicks per second", 13, 1, 20, 0);
    private final Setting startDelay = addNumber("Start Delay", "Milliseconds before the first click", 0, 0, 1000, 0);
    private final Setting randomization = addChoice("Randomization", "How the gaps between clicks vary",
            List.of("Normal", "Extra", "Extra+"), 2);
    private final Setting holdToClick = addBoolean("Hold to Click", "Only click while you hold right", true);
    private final Setting jitter = addBoolean("Jitter", "Shake the aim a little with each click", false);
    private final Setting blocksOnly = addBoolean("Blocks Only", "Only click holding blocks", false);

    private final SplittableRandom random = new SplittableRandom();
    private final ClickTiming timing = new ClickTiming(random);
    private long heldSince = -1;
    private long nextEventAt = -1;
    private long releaseFor;
    private boolean down;
    private double jitterYaw;
    private double jitterPitch;
    private double jitterSteps;
    private double jitterLeft;
    private float pendingYaw;
    private float pendingPitch;

    public RightClicker() {
        super("RightClicker", "Clicks right for you while you hold it", Category.COMBAT, Subcategory.BASIC);
    }

    @Override
    public String suffix() {
        return minCps.intValue() + "-" + maxCps.intValue() + "cps";
    }

    @Override
    public void onDisable(Context context) {
        release();
        heldSince = -1;
    }

    @Subscribe
    public void onGameLoop(GameLoopEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        boolean active = player != null && mc.currentScreen == null && mc.inGameHasFocus && !Client.gui().visible()
                && (!holdToClick.bool() || Mouse.isButtonDown(RIGHT))
                && (!blocksOnly.bool() || holdingBlock(player));
        long now = System.currentTimeMillis();
        if (!active) {
            release();
            heldSince = -1;
            return;
        }
        applyJitter(player);
        if (heldSince < 0) {
            heldSince = now;
            nextEventAt = -1;
        }
        if (now - heldSince < ACTIVATION_MS + startDelay.intValue() || nextEventAt >= 0 && now < nextEventAt) {
            return;
        }
        if (down) {
            InputHookManager.queueMouseButton(RIGHT, false);
            down = false;
            nextEventAt = now + releaseFor;
            return;
        }
        long gap = nextGap();
        long hold = holdFor(gap);
        releaseFor = gap - hold;
        InputHookManager.queueMouseButton(RIGHT, true);
        down = true;
        nextEventAt = now + hold;
        if (jitter.bool()) {
            startJitter();
        }
    }

    /* The press takes the rest of the gap; a faster rate lets go of it sooner. */
    private long holdFor(long gap) {
        double scale = (100L - Math.min(gap, 99L) + 45L) / 100.0;
        double releaseShare = (30 + random.nextInt(-9, 10) + 40.0 * scale) / 100.0;
        return (long) (gap * (1.0 - releaseShare));
    }

    private long nextGap() {
        int low = Math.min(minCps.intValue(), maxCps.intValue());
        int high = Math.max(minCps.intValue(), maxCps.intValue());
        long gap = timing.nextDelay(randomization.choice(), low, high) - 5L;
        return gap - 50L <= 0L ? 45L : gap;
    }

    private void release() {
        if (down) {
            InputHookManager.queueMouseButton(RIGHT, false);
            down = false;
        }
        nextEventAt = -1;
    }

    private void startJitter() {
        jitterYaw = random.nextDouble(-JITTER_COUNTS, JITTER_COUNTS);
        jitterPitch = random.nextDouble(-JITTER_COUNTS, JITTER_COUNTS);
        jitterSteps = (Math.abs(jitterYaw) + Math.abs(jitterPitch)) * 0.45;
        jitterLeft = jitterSteps;
    }

    /* The shake advances a step each tick and is turned in whole mouse counts each frame. */
    @Override
    public void onTick(Context context) {
        if (jitter.bool() && jitterLeft > 0.0) {
            pendingYaw += (float) (jitterYaw / jitterSteps);
            pendingPitch += (float) (jitterPitch / jitterSteps);
            jitterLeft -= 1.0;
        }
    }

    private void applyJitter(EntityPlayerSP player) {
        if (!jitter.bool()) {
            return;
        }
        int yaw = (int) pendingYaw;
        int pitch = (int) -pendingPitch;
        if (yaw == 0 && pitch == 0) {
            return;
        }
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        float counts = sensitivity * sensitivity * sensitivity * 8.0f;
        player.setAngles(yaw * counts, pitch * counts);
        pendingYaw -= yaw;
        pendingPitch += pitch;
    }

    private static boolean holdingBlock(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }
}
