package cobble.module.combat;

import cobble.event.GameLoopEvent;
import cobble.event.Subscribe;
import cobble.event.TickStartEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.player.InventoryManager;
import cobble.ui.Client;
import cobble.util.ClientUtils;
import cobble.util.InputHookManager;
import java.util.SplittableRandom;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

/**
 * Cobble's AutoClicker: clicks while the left button is held, spacing presses
 * with a unified, biological model of human timing (continuous motor drift,
 * physiological tremor, downstroke-recoil cycles, tracking adjustments,
 * and prospective deviation validation) instead of synthetic modes.
 */
public class AutoClicker extends Module {
    private static final double INVENTORY_INTERVAL_MS = 1000.0 / 16.0;
    private static final int PINK_ROWS = 16;
    private static final int ROLLING_BUFFER_SIZE = 32;

    private final Setting minCps = addNumber("Min CPS", "Lowest clicks per second", 14, 1, 20, 1);
    private final Setting maxCps = addNumber("Max CPS", "Highest clicks per second", 16, 1, 20, 1);
    private final Setting randomization = addBoolean("Randomization", "Human timing model instead of a uniform range", true);
    private final Setting randomizationPercent = addNumber("Randomization (%)", "How much of the human model applies",
            100, 0, 100, 0).visibleWhen(randomization::bool);
    private final Setting stable = addBoolean("Stable CPS", "Click at one fixed rate", false);
    private final Setting stableCps = addNumber("CPS (stable)", "Fixed clicks per second", 17, 1, 25, 0)
            .visibleWhen(stable::bool);
    private final Setting weaponOnly = addBoolean("Weapon Only", "Only click holding a sword or an axe", false);
    private final Setting breakBlocks = addBoolean("Break Blocks", "Stop clicking while looking at a block", false);
    private final Setting inventory = addBoolean("Inventory", "Shift and hold left in a container to quick-move", true);

    private final SplittableRandom random = new SplittableRandom();
    private double nextClickAt = -1;
    private boolean breaking;
    private GuiContainer inventoryGui;
    private Slot inventorySlot;
    private double nextInventoryClickAt = -1;

    private long sessionStart;
    private float fatigue;
    private float fatigueTarget;
    private int fatigueClicksLeft;
    private float burst;
    private int burstClicksLeft;
    private final float[] pinkRows = new float[PINK_ROWS];
    private int pinkCounter;
    private float pink;
    private float tempo;

    private final float[] recentIntervals = new float[ROLLING_BUFFER_SIZE];
    private int recentIndex = 0;
    private int recentCount = 0;
    private float lastInterval = 65.0f;

    public AutoClicker() {
        super("AutoClicker", "Clicks for you while you hold left", Category.COMBAT, Subcategory.BASIC);
    }

    @Override
    public String suffix() {
        return stable.bool() ? "Stable" : "Human";
    }

    @Override
    public void onEnable(Context context) {
        nextClickAt = -1;
        nextInventoryClickAt = -1;
        breaking = false;
        clearInventoryTarget();

        recentIndex = 0;
        recentCount = 0;
        lastInterval = 65.0f;

        sessionStart = System.currentTimeMillis();
        fatigue = 1.0f;
        fatigueTarget = 1.0f;
        fatigueClicksLeft = 0;
        burst = 1.0f;
        burstClicksLeft = 0;
        for (int row = 0; row < PINK_ROWS; ++row) {
            pinkRows[row] = uniform(-1.0f, 1.0f);
        }
        pinkCounter = 0;
        pink = 0.0f;
        tempo = clamp((minCps.number() + maxCps.number()) * 0.5f, 1.0f, 25.0f);
    }

    private void recordInterval(float interval) {
        recentIntervals[recentIndex] = interval;
        recentIndex = (recentIndex + 1) % ROLLING_BUFFER_SIZE;
        if (recentCount < ROLLING_BUFFER_SIZE) {
            recentCount++;
        }
        lastInterval = interval;
    }

    private float prospectiveStdDev(float cand, int count) {
        if (recentCount < count - 1) {
            return 35.0f;
        }
        int n = Math.min(count - 1, recentCount);
        float sum = cand;
        for (int i = 0; i < n; i++) {
            int idx = (recentIndex - 1 - i + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;
            sum += recentIntervals[idx];
        }
        int total = n + 1;
        float mean = sum / total;
        float sqDiff = (cand - mean) * (cand - mean);
        for (int i = 0; i < n; i++) {
            int idx = (recentIndex - 1 - i + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;
            float diff = recentIntervals[idx] - mean;
            sqDiff += diff * diff;
        }
        return (float) Math.sqrt(sqDiff / (total - 1));
    }

    private float prospectiveMean(float cand, int count) {
        if (recentCount < count - 1) {
            return cand;
        }
        int n = Math.min(count - 1, recentCount);
        float sum = cand;
        for (int i = 0; i < n; i++) {
            int idx = (recentIndex - 1 - i + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;
            sum += recentIntervals[idx];
        }
        return sum / (n + 1);
    }

    @Override
    public void onDisable(Context context) {
        InputHookManager.simulateMouseButton(0, false);
        nextClickAt = -1;
        clearInventoryTarget();
    }

    public void setInventoryTarget(GuiContainer gui, Slot slot) {
        inventoryGui = gui;
        inventorySlot = slot;
    }

    private void clearInventoryTarget() {
        inventoryGui = null;
        inventorySlot = null;
    }

    @Subscribe
    public void onGameLoop(GameLoopEvent event) {
        if (!ClientUtils.isInWorld() || mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) {
            breaking = false;
            if (nextClickAt >= 0) {
                InputHookManager.simulateMouseButton(0, false);
            }
            nextClickAt = -1;
            return;
        }
        if (Client.gui().visible() || mc.currentScreen != null || !mc.inGameHasFocus || !Mouse.isButtonDown(0)) {
            breaking = false;
            if (nextClickAt >= 0) {
                InputHookManager.simulateMouseButton(0, false);
            }
            nextClickAt = -1;
            return;
        }
        if (weaponOnly.bool() && !holdingWeapon()) {
            if (nextClickAt >= 0) {
                InputHookManager.simulateMouseButton(0, false);
            }
            nextClickAt = -1;
            return;
        }
        /* The physical press already holds the attack binding down; clicking
         * over it would restart the dig every time. */
        if (breakBlocks.bool() && targetingBlock()) {
            breaking = true;
            return;
        }
        if (breaking) {
            breaking = false;
            nextClickAt = -1;
        }

        double now = System.nanoTime() / 1.0e6;
        if (nextClickAt < 0) {
            nextClickAt = now + nextInterval();
            return;
        }
        // A stalled frame would otherwise pay back every missed click at once.
        if (now - nextClickAt > 250.0) {
            nextClickAt = now;
        }
        if (nextClickAt <= now) {
            mc.entityRenderer.getMouseOver(1.0f);
            mc.leftClickCounter = 0;
            mc.clickMouse();
            mc.leftClickCounter = 0;
            nextClickAt = now + nextInterval();
        }
    }

    @Subscribe
    public void onTickStart(TickStartEvent event) {
        GuiContainer gui = inventoryGui;
        Slot slot = inventorySlot;
        if (!inventoryClickActive(gui)) {
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
        if (slot != null && slot.getHasStack() && slot.canTakeStack(mc.thePlayer)) {
            mc.playerController.windowClick(gui.inventorySlots.windowId, slot.slotNumber, 0, 1, mc.thePlayer);
        }
    }

    private boolean inventoryClickActive(GuiContainer gui) {
        if (!inventory.bool() || !ClientUtils.isInWorld() || gui == null || gui != mc.currentScreen
                || Client.gui().visible()) {
            return false;
        }
        InventoryManager manager = ModuleManager.get(InventoryManager.class);
        return gui.inventorySlots == mc.thePlayer.openContainer
                && GuiScreen.isShiftKeyDown()
                && Mouse.isButtonDown(0)
                && mc.thePlayer.inventory.getItemStack() == null
                && !manager.isExecutingPlan();
    }

    private static boolean holdingWeapon() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return false;
        }
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemAxe;
    }

    private static boolean targetingBlock() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(hit.getBlockPos()).getBlock();
        return block != Blocks.air && !(block instanceof BlockLiquid);
    }

    private double nextInterval() {
        if (stable.bool()) {
            return 1000.0 / stableCps.number();
        }
        float low = Math.min(minCps.number(), maxCps.number());
        float high = Math.max(minCps.number(), maxCps.number());
        if (!randomization.bool()) {
            float targetTempo = uniform(low, high);
            float base = 1000.0f / Math.max(targetTempo, 1.0f);
            float interval = uniform(base * 0.85f, base * 1.15f);
            recordInterval(interval);
            return interval;
        }
        updateFatigue();
        updateBurst();
        return humanInterval(low, high);
    }

    private float humanInterval(float low, float high) {
        low = clamp(low, 1.0f, 20.0f);
        high = clamp(high, low, 20.0f);
        float intensity = randomizationPercent.number() / 100.0f;

        // Biological tempo wandering with 1/f pink noise and muscle inertia
        float targetTempo = uniform(low - 0.5f, high + 0.5f) + nextPink() * (0.25f * intensity);
        tempo += (targetTempo - tempo) * 0.10f;
        tempo = clamp(tempo, 1.0f, 25.0f);

        float base = 1000.0f / tempo;
        // Modulate base by natural fatigue and brief burst impulses
        base *= 1.0f + (fatigue - 1.0f) * (0.5f * intensity);
        base *= 1.0f + (burst - 1.0f) * (0.5f * intensity);

        float last = (recentCount > 0) ? lastInterval : base;
        float fastThresh = Math.max(30.0f, base * 0.70f);
        float slowThresh = base * 1.30f;

        boolean wasFast = (last < fastThresh);
        boolean wasSlow = (last > slowThresh);

        float interval;
        if (wasFast) {
            // Biological recoil: after a fast downstroke (< fastThresh), the finger resets.
            // Generates a natural recovery tap (1.18 to 1.45x of base + subtle motor noise)
            double u1 = Math.max(1e-9, unit());
            double u2 = unit();
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            interval = base * uniform(1.18f, 1.45f) + (float) (3.0 * z * intensity);
        } else if (wasSlow) {
            // Resumption: after a hesitation or tracking lift (> slowThresh), re-engage with normal cadence
            double u1 = Math.max(1e-9, unit());
            double u2 = unit();
            double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            interval = base * uniform(0.75f, 1.02f) + (float) (3.0 * z * intensity);
        } else {
            // Natural human distribution: mixture of mechanical micro-bounces,
            // tracking hesitations, and log-normal motor actuation noise
            double roll = unit();
            if (roll < 0.14 * intensity) {
                // Mechanical switch debounce / rapid double tap
                interval = base * uniform(0.48f, 0.65f);
            } else if (roll < 0.22 * intensity) {
                // Aim correction / tracking hesitation (micro-pause)
                interval = base + uniform(28.0f, 52.0f) * intensity;
            } else {
                // Primary biological motor tap: log-normal distribution with natural motor variance
                double u1 = Math.max(1e-9, unit());
                double u2 = unit();
                double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
                float s = uniform(0.32f, 0.40f) * intensity;
                interval = base * (float) Math.exp(s * z - 0.5 * s * s);
            }
        }

        interval = clamp(interval, 25.0f, 150.0f);

        // Anti-mechanical equality: ensure adjacent intervals differ by at least 3ms with organic variation
        if (recentCount > 0 && Math.abs(interval - last) < 3.0f) {
            float delta = uniform(4.0f, 10.0f);
            interval += (interval >= last ? delta : -delta);
            interval = clamp(interval, 25.0f, 150.0f);
        }

        // Active Deviation Floor Validator (Intave clickpatterns & Vulcan safe):
        // Ensure that prospective standard deviation across windows (6, 8, 10, 16)
        // never falls below human biological variance threshold.
        float minTargetStd = Math.max(24.0f, base * 0.32f) * intensity;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean tooLow = false;
            int[] windows = {6, 8, 10, 16};
            for (int w : windows) {
                float pStd = prospectiveStdDev(interval, w);
                if (pStd < minTargetStd) {
                    tooLow = true;
                    float pMean = prospectiveMean(interval, w);
                    if (interval >= pMean) {
                        interval = clamp(interval + uniform(20.0f, 34.0f), 25.0f, 150.0f);
                    } else {
                        interval = clamp(interval - uniform(16.0f, 26.0f), 25.0f, 150.0f);
                    }
                    break;
                }
            }
            if (!tooLow) {
                break;
            }
        }

        interval = clamp(interval, 25.0f, 150.0f);
        recordInterval(interval);
        return interval;
    }

    private void updateFatigue() {
        float sessionLoad = Math.min((System.currentTimeMillis() - sessionStart) / 1000L / 60.0f * 0.004f, 0.10f);
        if (fatigueClicksLeft <= 0) {
            fatigueTarget = 1.0f + sessionLoad + uniform(0.02f, 0.12f);
            fatigueClicksLeft = random.nextInt(40, 120);
        }
        --fatigueClicksLeft;
        fatigue += (fatigueTarget - fatigue) * 0.03f;
        fatigue = clamp(fatigue, 1.0f, 1.25f);
    }

    private void burstImpulse() {
        if (burstClicksLeft > 0) {
            if (--burstClicksLeft == 0) {
                burst = 1.0f;
            }
            return;
        }
        if (random.nextInt(101) > 8) {
            return;
        }
        burstClicksLeft = random.nextInt(3, 10);
        burst = random.nextInt(101) < 70 ? uniform(0.88f, 0.96f) : uniform(1.04f, 1.15f);
    }

    private void updateBurst() {
        burstImpulse();
    }

    /* Voss-McCartney: every counter bit that flips resamples its row, and the
     * power-of-two update rates sum to roughly a 1/f spectrum. */
    private float nextPink() {
        int changed = pinkCounter ^ ++pinkCounter;
        float sum = 0.0f;
        for (int row = 0; row < PINK_ROWS; ++row) {
            if ((changed & (1 << row)) != 0) {
                pinkRows[row] = uniform(-1.0f, 1.0f);
            }
            sum += pinkRows[row];
        }
        pink += (sum / PINK_ROWS - pink) * 0.4f;
        return pink;
    }

    private double unit() {
        return random.nextDouble();
    }

    private float uniform(float low, float high) {
        return low + (float) random.nextDouble() * (high - low);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
