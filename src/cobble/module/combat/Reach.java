package cobble.module.combat;

import cobble.event.AttackEntityEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.util.raytrace.PointedObjectOverrider;
import cobble.util.team.TeamUtils;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Cobble's Reach. The native client slid the target's hitbox toward the player
 * so vanilla's crosshair ray would find it; here the ray itself goes further:
 * a player past vanilla's 3 blocks but inside the distance becomes what the
 * crosshair points at, unless a block is in the way.
 *
 * <p>The chance is rolled every tick, or once per target: then a won roll lasts
 * for one hit, and the next roll waits half a second.
 */
public final class Reach extends Module implements PointedObjectOverrider {
    private static final double VANILLA_REACH = 3.0;
    private static final int PER_TARGET = 1;
    private static final int ROLL_COOLDOWN_TICKS = 10;
    /* How far above or below the player a target may stand with the vertical check on. */
    private static final double VERTICAL_TOLERANCE = 0.2;

    private final Setting distance = addNumber("Distance", "How far hits reach in blocks", 3.6f, 3, 6, 2);
    private final Setting weaponsOnly = addBoolean("Weapons Only", "Only while holding a sword or an axe", false);
    private final Setting sprintingOnly = addBoolean("Sprinting Only", "Only while sprinting", false);
    private final Setting movingOnly = addBoolean("Moving Only", "Only while moving", false);
    private final Setting hitChance = addNumber("Hit Chance", "Chance that the extra reach applies", 100, 0, 100, 0);
    private final Setting chanceMode = addChoice("Chance Mode",
            "Roll the chance every tick, or once per hit on a target", List.of("Each tick", "Per target"), 0);
    private final Setting tickDelay = addNumber("Tick Delay", "Ticks skipped between the ticks it applies on",
            0, 0, 10, 0).visibleWhen(() -> chanceMode.choice() != PER_TARGET);
    private final Setting verticalCheck = addBoolean("Vertical Check",
            "Leave players standing higher or lower than you alone", false);
    private final Setting disableInWater = addBoolean("Disable in Water", "No extra reach in water or lava", false);

    private final SplittableRandom random = new SplittableRandom();
    private int ticksSkipped;
    private boolean applies;
    private int rollCooldown;
    private EntityPlayer lastTarget;

    public Reach() {
        super("Reach", "Hits players a little past vanilla's reach", Category.COMBAT, Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return String.format("%.2f", distance.number());
    }

    @Override
    public void onEnable(Context context) {
        ticksSkipped = 0;
        applies = false;
        rollCooldown = 0;
        lastTarget = null;
    }

    @Override
    public void onTick(Context context) {
        if (chanceMode.choice() == PER_TARGET) {
            rollPerTarget();
            return;
        }
        if (tickDelay.intValue() > 0 && ++ticksSkipped <= tickDelay.intValue()) {
            applies = false;
            return;
        }
        ticksSkipped = 0;
        applies = rolled();
    }

    /* A new target forgets the old roll; the same one gets a fresh roll once the last has cooled down. */
    private void rollPerTarget() {
        EntityPlayerSP player = mc.thePlayer;
        if (rollCooldown > 0) {
            --rollCooldown;
        }
        EntityPlayer target = player == null || mc.theWorld == null ? null : closestTarget(player, 1.0f);
        if (target == null || lastTarget != null && target != lastTarget) {
            lastTarget = null;
            applies = false;
            return;
        }
        if (rollCooldown == 0 && allowed(player)) {
            applies = rolled();
            if (applies) {
                rollCooldown = ROLL_COOLDOWN_TICKS;
            }
        }
        lastTarget = target;
    }

    @Subscribe(priority = Subscribe.Priority.LOWEST)
    public void onAttack(AttackEntityEvent event) {
        if (chanceMode.choice() == PER_TARGET && !event.cancelled()) {
            applies = false;
        }
    }

    private boolean rolled() {
        return hitChance.intValue() >= 100 || random.nextInt(100) < hitChance.intValue();
    }

    @Override
    public boolean shouldOverridePointedObject() {
        EntityPlayerSP player = mc.thePlayer;
        if (!enabled() || !applies || player == null || mc.theWorld == null || mc.currentScreen != null
                || mc.getRenderViewEntity() != player) {
            return false;
        }
        return allowed(player);
    }

    private boolean allowed(EntityPlayerSP player) {
        return (!weaponsOnly.bool() || Game.holdingWeapon())
                && (!sprintingOnly.bool() || player.isSprinting())
                && (!movingOnly.bool() || player.motionX * player.motionX + player.motionZ * player.motionZ >= 1.0E-4)
                && (!disableInWater.bool() || !player.isInWater() && !player.isInLava());
    }

    @Override
    public void applyPointedObjectOverride(float partialTicks) {
        EntityPlayerSP player = mc.thePlayer;
        Vec3 eyes = player.getPositionEyes(partialTicks);
        EntityPlayer closest = closestTarget(player, partialTicks);
        if (closest == null) {
            return;
        }
        Vec3 hit = intercept(closest, eyes, reachEnd(player, eyes, partialTicks));
        /* Inside vanilla reach the game already finds it on its own. */
        if (eyes.distanceTo(hit) <= VANILLA_REACH) {
            return;
        }
        mc.objectMouseOver = new MovingObjectPosition(closest, hit);
        mc.pointedEntity = closest;
    }

    /* The nearest player the stretched crosshair ray meets before any block, or null. */
    private EntityPlayer closestTarget(EntityPlayerSP player, float partialTicks) {
        Vec3 eyes = player.getPositionEyes(partialTicks);
        Vec3 end = reachEnd(player, eyes, partialTicks);
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);
        double closestDistance = block != null ? eyes.distanceTo(block.hitVec) : distance.number();
        EntityPlayer closest = null;
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == player || other.isInvisible() || AntiBot.shouldHideEntity(other)
                    || TeamUtils.isTeammate(mc, other)
                    || verticalCheck.bool() && Math.abs(other.posY - player.posY) > VERTICAL_TOLERANCE) {
                continue;
            }
            Vec3 hit = intercept(other, eyes, end);
            if (hit != null && eyes.distanceTo(hit) < closestDistance) {
                closest = other;
                closestDistance = eyes.distanceTo(hit);
            }
        }
        return closest;
    }

    private Vec3 reachEnd(EntityPlayerSP player, Vec3 eyes, float partialTicks) {
        double reach = distance.number();
        Vec3 look = player.getLook(partialTicks);
        return eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
    }

    private static Vec3 intercept(EntityPlayer target, Vec3 eyes, Vec3 end) {
        double border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition hit = box.calculateIntercept(eyes, end);
        return hit == null ? null : hit.hitVec;
    }
}
