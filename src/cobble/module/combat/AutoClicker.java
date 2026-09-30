package cobble.module.combat;

import cobble.event.PrePlayerInteractEvent;
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
import net.minecraft.client.settings.KeyBinding;
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
 * with a model of human timing (a wandering tempo, fatigue, bursts, log-normal
 * jitter and pink noise) instead of a flat random range.
 *
 * <p>The server times the press, so each generated interval is the full gap
 * from one press to the next.
 */
public class AutoClicker extends Module {
    private static final double INVENTORY_INTERVAL_MS = 1000.0 / 16.0;
    private static final int PINK_ROWS = 16;

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
    private boolean recovering;
    private float burst;
    private int burstClicksLeft;
    private float sigma;
    private float sigmaTarget;
    private float sigmaSessionOffset;
    private int sigmaClicksLeft;
    private float tempoStepScale;
    private float tempoPull;
    private float hesitationChance;
    private float doubleTapChance;
    private int shapeClicksLeft;
    private final float[] pinkRows = new float[PINK_ROWS];
    private int pinkCounter;
    private float pink;
    private float pinkAmplitude;
    private float tempo;
    private int tempoClicksLeft;

    public AutoClicker() {
        super("AutoClicker", "Clicks for you while you hold left", Category.COMBAT, Subcategory.BASIC);
    }

    @Override
    public String suffix() {
        return stable.bool() ? "Stable" : "";
    }

    @Override
    public void onEnable(Context context) {
        nextClickAt = -1;
        nextInventoryClickAt = -1;
        breaking = false;
        clearInventoryTarget();

        sessionStart = System.currentTimeMillis();
        fatigue = 1.0f;
        fatigueTarget = 1.0f;
        fatigueClicksLeft = 0;
        recovering = true;
        burst = 1.0f;
        burstClicksLeft = 0;
        sigma = uniform(0.12f, 0.32f);
        sigmaTarget = sigma;
        sigmaClicksLeft = 0;
        sigmaSessionOffset = uniform(-0.04f, 0.04f);
        tempoStepScale = uniform(0.08f, 0.24f);
        tempoPull = uniform(0.02f, 0.12f);
        hesitationChance = uniform(0.01f, 0.06f);
        doubleTapChance = uniform(0.005f, 0.04f);
        shapeClicksLeft = 0;
        for (int row = 0; row < PINK_ROWS; ++row) {
            pinkRows[row] = uniform(-1.0f, 1.0f);
        }
        pinkCounter = 0;
        pink = 0.0f;
        pinkAmplitude = uniform(0.05f, 0.17f);
        tempo = clamp((minCps.number() + maxCps.number()) * 0.5f, 1.0f, 25.0f);
        tempoClicksLeft = 0;
    }

    @Override
    public void onDisable(Context context) {
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
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!ClientUtils.isInWorld()) {
            return;
        }
        if (Client.gui().visible() || mc.currentScreen != null || !mc.inGameHasFocus || !Mouse.isButtonDown(0)) {
            breaking = false;
            nextClickAt = -1;
            return;
        }
        if (weaponOnly.bool() && !holdingWeapon()) {
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
        // A stalled tick would otherwise pay back every missed click at once.
        if (now - nextClickAt > 250.0) {
            nextClickAt = now;
        }
        int attackKey = mc.gameSettings.keyBindAttack.getKeyCode();
        while (nextClickAt <= now) {
            KeyBinding.onTick(attackKey);
            InputHookManager.simulateMouseButton(0, true);
            nextClickAt += nextInterval();
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
            return 1000.0 / Math.max(uniform(low, high), 0.1f);
        }
        updateFatigue();
        updateBurst();
        return humanInterval(low, high);
    }

    private float humanInterval(float low, float high) {
        low = clamp(low, 1.0f, 20.0f);
        high = clamp(high, low, 20.0f);
        float intensity = randomizationPercent.number() / 100.0f;
        float center = (low + high) * 0.5f;
        float lowEff = center + (low - center) * intensity;
        float highEff = center + (high - center) * intensity;

        updateShape();
        if (tempoClicksLeft <= 0) {
            double u = (unit() + unit() + unit()) / 3.0;
            float target = lowEff + (float) u * (highEff - lowEff);
            tempo = clamp(0.5f * tempo + 0.5f * target, lowEff, highEff);
            tempoClicksLeft = 4 + (int) (unit() * 9.0);
        }
        --tempoClicksLeft;
        float step = (float) (unit() - unit()) * (highEff - lowEff) * tempoStepScale * intensity;
        tempo = clamp(tempo + step + (center - tempo) * tempoPull, lowEff, highEff);

        float interval = 1000.0f / Math.max(tempo, 1.0f);
        interval *= 1.0f + (fatigue - 1.0f) * intensity;
        interval *= 1.0f + (burst - 1.0f) * intensity;

        /* Log-normal jitter gives the right-skewed, heavy-tailed spread human
         * intervals have; the -sigma^2/2 term keeps the mean unbiased. Clamping
         * before scaling keeps a zero intensity able to reach a constant rate. */
        updateSigma();
        double u1 = Math.max(1e-9, unit());
        double u2 = unit();
        double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        double scaledSigma = clamp(sigma + sigmaSessionOffset, 0.08f, 0.42f) * intensity;
        interval *= (float) Math.exp(scaledSigma * z - 0.5 * scaledSigma * scaledSigma);
        interval *= (float) Math.exp(nextPink() * pinkAmplitude * intensity);

        double quirk = unit();
        float hesitation = hesitationChance * intensity;
        float doubleTap = doubleTapChance * intensity;
        if (quirk < hesitation) {
            interval += 40.0f + (float) unit() * 160.0f;
        } else if (quirk < hesitation + doubleTap) {
            interval *= 0.70f + (float) unit() * 0.12f;
        }

        float floor = Math.max(1000.0f / (high * 1.08f), 48.0f);
        float ceiling = 1000.0f / Math.max(low * 0.45f, 1.0f);
        return clamp(interval, floor, ceiling);
    }

    private void updateFatigue() {
        float sessionLoad = Math.min((System.currentTimeMillis() - sessionStart) / 1000L / 60.0f * 0.004f, 0.10f);
        if (fatigueClicksLeft <= 0) {
            recovering = !recovering;
            if (recovering) {
                fatigueTarget = 1.0f + sessionLoad * uniform(0.25f, 0.55f);
                fatigueClicksLeft = random.nextInt(40, 101);
            } else {
                fatigueTarget = 1.0f + sessionLoad + uniform(0.08f, 0.18f);
                fatigueClicksLeft = random.nextInt(90, 221);
            }
        }
        --fatigueClicksLeft;
        fatigue += (fatigueTarget - fatigue) * (recovering ? 0.045f : 0.018f);
        fatigue = clamp(fatigue, 1.0f, 1.30f);
    }

    private void updateBurst() {
        if (burstClicksLeft > 0) {
            if (--burstClicksLeft == 0) {
                burst = 1.0f;
            }
            return;
        }
        if (random.nextInt(101) > 8) {
            return;
        }
        burstClicksLeft = random.nextInt(3, 13);
        burst = random.nextInt(101) < 70 ? uniform(0.85f, 0.95f) : uniform(1.05f, 1.20f);
    }

    private void updateSigma() {
        if (sigmaClicksLeft <= 0) {
            sigmaTarget = uniform(0.08f, 0.44f);
            sigmaClicksLeft = random.nextInt(40, 181);
        }
        --sigmaClicksLeft;
        sigma = clamp(sigma + (sigmaTarget - sigma) * 0.025f, 0.08f, 0.44f);
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

    /* Shifting the whole shape now and then keeps windows that straddle a
     * change from sharing one statistical fingerprint. */
    private void updateShape() {
        if (shapeClicksLeft > 0) {
            --shapeClicksLeft;
            return;
        }
        tempoStepScale = uniform(0.08f, 0.24f);
        tempoPull = uniform(0.02f, 0.12f);
        hesitationChance = uniform(0.01f, 0.06f);
        doubleTapChance = uniform(0.005f, 0.04f);
        shapeClicksLeft = random.nextInt(60, 201);
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
