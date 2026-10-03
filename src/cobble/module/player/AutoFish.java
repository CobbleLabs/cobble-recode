package cobble.module.player;

import cobble.core.Time;
import cobble.event.PacketReceiveEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.SplittableRandom;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

/**
 * Fishes for you. Once the bobber has settled on the water a bite is the tug
 * that pulls it under, read from its motion or from the velocity the server
 * sends for it; then it reels in and casts again a second later. A rod that
 * breaks is replaced from the inventory.
 */
public final class AutoFish extends Module {
    private static final double STILL = 0.05;
    private static final long SETTLE_MS = 1000;
    private static final long RECAST_MS = 1000;
    private static final long GROUND_MS = 3000;
    /* The bobber's box is sampled in this many slices for water. */
    private static final int SLICES = 5;

    private final Setting recastGround = addBoolean("Recast ground", "Recast when the hook lands on the ground", false);
    private final Setting recastCaught = addBoolean("Recast caught", "Recast when the hook catches an entity", false);
    private final Setting refillRods = addBoolean("Refill rods", "Replace a broken rod from your inventory", true);
    private final Setting clickMin = addNumber("Click delay min", "Least milliseconds before the refill click", 75,
            50, 200, 0).visibleWhen(() -> this.refillRods.bool());
    private final Setting clickMax = addNumber("Click delay max", "Most milliseconds before the refill click", 125,
            50, 200, 0).visibleWhen(() -> this.refillRods.bool());

    private final SplittableRandom random = new SplittableRandom();
    private boolean pressed;
    private boolean cast;
    private boolean biteWatch;
    private volatile boolean velocityBite;
    private double sinking;
    private long castAt;
    private long stillSince;
    private long groundSince;
    private boolean refilling;
    private long refillClickAt = -1;

    public AutoFish() {
        super("AutoFish", "Fishes for you", Category.PLAYER, Subcategory.BASIC);
    }

    @Override
    public void onEnable(Context context) {
        long now = Time.nowMillis();
        pressed = false;
        cast = false;
        biteWatch = false;
        velocityBite = false;
        sinking = 0.0;
        castAt = now;
        stillSince = now;
        groundSince = now;
        refilling = false;
        refillClickAt = -1;
    }

    @Override
    public void onDisable(Context context) {
        if (pressed) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            pressed = false;
        }
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        if (!holdingRod(player)) {
            findRod(player, context);
            return;
        }
        if (mc.currentScreen != null) {
            endRefill();
            return;
        }
        if (pressed) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            pressed = false;
            return;
        }
        long now = Time.nowMillis();
        EntityFishHook hook = player.fishEntity;
        if (hook == null) {
            recast(false, now);
            return;
        }
        if (hook.caughtEntity != null) {
            if (recastCaught.bool()) {
                recast(true, now);
            } else {
                setEnabled(false, context);
            }
            return;
        }
        if (!inLiquid(hook)) {
            if (recastGround.bool() && now - groundSince >= GROUND_MS) {
                recast(true, now);
            }
            return;
        }
        groundSince = now;
        if (!biteWatch) {
            if (Math.abs(hook.motionX) + Math.abs(hook.motionY) + Math.abs(hook.motionZ) > STILL) {
                stillSince = now;
            } else if (now - stillSince >= SETTLE_MS) {
                biteWatch = true;
            }
            return;
        }
        sinking = hook.motionY <= -0.1 ? sinking + hook.motionY : 0.0;
        if (sinking <= -STILL || velocityBite) {
            recast(true, now);
            biteWatch = false;
            velocityBite = false;
        }
    }

    /* The server pulls the bobber straight down when a fish takes it. */
    @Subscribe
    public void onPacketReceive(PacketReceiveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!biteWatch || player == null || player.fishEntity == null
                || !(event.getPacket() instanceof S12PacketEntityVelocity velocity)
                || velocity.getEntityID() != player.fishEntity.getEntityId()) {
            return;
        }
        if (velocity.getMotionX() == 0 && velocity.getMotionZ() == 0 && velocity.getMotionY() / 8000.0 <= -STILL) {
            velocityBite = true;
        }
    }

    /* Reeling in a hook that is out happens at once; a fresh cast waits a second after the last. */
    private void recast(boolean hookOut, long now) {
        if (hookOut || !cast || now - castAt >= RECAST_MS) {
            KeyBinding use = mc.gameSettings.keyBindUseItem;
            KeyBinding.setKeyBindState(use.getKeyCode(), true);
            KeyBinding.onTick(use.getKeyCode());
            pressed = true;
            castAt = now;
            groundSince = now;
            stillSince = now;
            cast = true;
            sinking = 0.0;
        }
    }

    /* A rod on the hotbar is picked up; failing that one is swapped in from the inventory to the first slot. */
    private void findRod(EntityPlayerSP player, Context context) {
        for (int slot = 0; slot < 9; ++slot) {
            if (isRod(player.inventory.mainInventory[slot])) {
                if (mc.currentScreen != null) {
                    endRefill();
                } else {
                    player.inventory.currentItem = slot;
                }
                return;
            }
        }
        int stored = -1;
        for (int slot = 9; slot < 36 && stored < 0; ++slot) {
            if (isRod(player.inventory.mainInventory[slot])) {
                stored = slot;
            }
        }
        if (!refillRods.bool() || stored < 0) {
            setEnabled(false, context);
            return;
        }
        if (!(mc.currentScreen instanceof GuiInventory)) {
            if (mc.currentScreen == null) {
                refilling = true;
                mc.displayGuiScreen(new GuiInventory(player));
            } else {
                endRefill();
            }
            return;
        }
        long now = Time.nowMillis();
        if (refillClickAt < 0) {
            int low = Math.min(clickMin.intValue(), clickMax.intValue());
            refillClickAt = now + low + random.nextInt(Math.max(clickMin.intValue(), clickMax.intValue()) - low + 1);
        } else if (now >= refillClickAt) {
            mc.playerController.windowClick(player.inventoryContainer.windowId, stored, 0, 2, player);
            refillClickAt = -1;
        }
    }

    private void endRefill() {
        if (refilling) {
            refilling = false;
            refillClickAt = -1;
            mc.thePlayer.closeScreen();
        }
    }

    private boolean inLiquid(EntityFishHook hook) {
        if (hook.isInWater()) {
            return true;
        }
        AxisAlignedBB box = hook.getEntityBoundingBox();
        double height = box.maxY - box.minY;
        for (int slice = 0; slice < SLICES; ++slice) {
            AxisAlignedBB part = new AxisAlignedBB(box.minX, box.minY + height * slice / SLICES, box.minZ, box.maxX,
                    box.minY + height * (slice + 1) / SLICES, box.maxZ);
            if (mc.theWorld.isMaterialInBB(part, Material.water)) {
                return true;
            }
        }
        return waterBelow(hook);
    }

    /* Water within three blocks under the hook, with only air between. */
    private boolean waterBelow(EntityFishHook hook) {
        for (double y = hook.posY; y >= 0.0 && y >= hook.posY - 3.0; y -= 1.0) {
            Material material = mc.theWorld.getBlockState(new BlockPos(hook.posX, y, hook.posZ)).getBlock()
                    .getMaterial();
            if (material == Material.water) {
                return true;
            }
            if (material != Material.air) {
                return false;
            }
        }
        return false;
    }

    private static boolean holdingRod(EntityPlayerSP player) {
        return isRod(player.getHeldItem());
    }

    private static boolean isRod(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemFishingRod;
    }
}
