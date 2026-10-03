package cobble.module.combat;

import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.util.raytrace.PointedObjectOverrider;
import cobble.util.team.TeamUtils;
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
 */
public final class Reach extends Module implements PointedObjectOverrider {
    private static final double VANILLA_REACH = 3.0;

    private final Setting distance = addNumber("Distance", "How far hits reach in blocks", 3.6f, 3, 6, 2);
    private final Setting weaponsOnly = addBoolean("Weapons Only", "Only while holding a sword or an axe", false);
    private final Setting sprintingOnly = addBoolean("Sprinting Only", "Only while sprinting", false);
    private final Setting movingOnly = addBoolean("Moving Only", "Only while moving", false);
    private final Setting hitChance = addNumber("Hit Chance", "Chance each tick that the extra reach applies",
            100, 0, 100, 0);
    private final Setting tickDelay = addNumber("Tick Delay", "Ticks skipped between the ticks it applies on",
            0, 0, 10, 0);

    private final SplittableRandom random = new SplittableRandom();
    private int ticksSkipped;
    private boolean applies;

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
    }

    @Override
    public void onTick(Context context) {
        if (tickDelay.intValue() > 0 && ++ticksSkipped <= tickDelay.intValue()) {
            applies = false;
            return;
        }
        ticksSkipped = 0;
        applies = hitChance.intValue() >= 100 || random.nextInt(100) < hitChance.intValue();
    }

    @Override
    public boolean shouldOverridePointedObject() {
        EntityPlayerSP player = mc.thePlayer;
        if (!enabled() || !applies || player == null || mc.theWorld == null || mc.currentScreen != null
                || mc.getRenderViewEntity() != player) {
            return false;
        }
        return (!weaponsOnly.bool() || Game.holdingWeapon())
                && (!sprintingOnly.bool() || player.isSprinting())
                && (!movingOnly.bool() || player.motionX * player.motionX + player.motionZ * player.motionZ >= 1.0E-4);
    }

    @Override
    public void applyPointedObjectOverride(float partialTicks) {
        EntityPlayerSP player = mc.thePlayer;
        double reach = distance.number();
        Vec3 eyes = player.getPositionEyes(partialTicks);
        Vec3 look = player.getLook(partialTicks);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);
        double limit = block != null ? eyes.distanceTo(block.hitVec) : reach;

        EntityPlayer closest = null;
        Vec3 closestHit = null;
        double closestDistance = limit;
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == player || other.isInvisible() || AntiBot.shouldHideEntity(other)
                    || TeamUtils.isTeammate(mc, other)) {
                continue;
            }
            double border = other.getCollisionBorderSize();
            AxisAlignedBB box = other.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = box.calculateIntercept(eyes, end);
            if (hit == null) {
                continue;
            }
            double hitDistance = eyes.distanceTo(hit.hitVec);
            if (hitDistance < closestDistance) {
                closest = other;
                closestHit = hit.hitVec;
                closestDistance = hitDistance;
            }
        }
        /* Inside vanilla reach the game already finds it on its own. */
        if (closest == null || closestDistance <= VANILLA_REACH) {
            return;
        }
        mc.objectMouseOver = new MovingObjectPosition(closest, closestHit);
        mc.pointedEntity = closest;
    }
}
