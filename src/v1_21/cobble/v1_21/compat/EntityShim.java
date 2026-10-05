package cobble.v1_21.compat;

import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 1.8.9's Entity on 1.21.11's. Position, motion and rotation are fields on
 * 1.8 and accessors here; 1.8's previous-tick and last-tick positions are
 * 1.21's last and last render ones, which both hold the previous tick.
 */
public final class EntityShim {
    private EntityShim() {}

    public static double posX(Entity entity) {
        return entity.getX();
    }

    public static double posY(Entity entity) {
        return entity.getY();
    }

    public static double posZ(Entity entity) {
        return entity.getZ();
    }

    public static void posX(Entity entity, double value) {
        entity.setPos(value, entity.getY(), entity.getZ());
    }

    public static void posY(Entity entity, double value) {
        entity.setPos(entity.getX(), value, entity.getZ());
    }

    public static void posZ(Entity entity, double value) {
        entity.setPos(entity.getX(), entity.getY(), value);
    }

    public static double prevPosX(Entity entity) {
        return entity.lastX;
    }

    public static double prevPosY(Entity entity) {
        return entity.lastY;
    }

    public static double prevPosZ(Entity entity) {
        return entity.lastZ;
    }

    public static void prevPosX(Entity entity, double value) {
        entity.lastX = value;
    }

    public static void prevPosY(Entity entity, double value) {
        entity.lastY = value;
    }

    public static void prevPosZ(Entity entity, double value) {
        entity.lastZ = value;
    }

    public static double lastTickPosX(Entity entity) {
        return entity.lastRenderX;
    }

    public static double lastTickPosY(Entity entity) {
        return entity.lastRenderY;
    }

    public static double lastTickPosZ(Entity entity) {
        return entity.lastRenderZ;
    }

    public static void lastTickPosX(Entity entity, double value) {
        entity.lastRenderX = value;
    }

    public static void lastTickPosY(Entity entity, double value) {
        entity.lastRenderY = value;
    }

    public static void lastTickPosZ(Entity entity, double value) {
        entity.lastRenderZ = value;
    }

    public static double motionX(Entity entity) {
        return entity.getVelocity().x;
    }

    public static double motionY(Entity entity) {
        return entity.getVelocity().y;
    }

    public static double motionZ(Entity entity) {
        return entity.getVelocity().z;
    }

    public static void motionX(Entity entity, double value) {
        Vec3d velocity = entity.getVelocity();
        entity.setVelocity(value, velocity.y, velocity.z);
    }

    public static void motionY(Entity entity, double value) {
        Vec3d velocity = entity.getVelocity();
        entity.setVelocity(velocity.x, value, velocity.z);
    }

    public static void motionZ(Entity entity, double value) {
        Vec3d velocity = entity.getVelocity();
        entity.setVelocity(velocity.x, velocity.y, value);
    }

    public static float rotationYaw(Entity entity) {
        return entity.getYaw();
    }

    public static float rotationPitch(Entity entity) {
        return entity.getPitch();
    }

    public static void rotationYaw(Entity entity, float value) {
        entity.setYaw(value);
    }

    public static void rotationPitch(Entity entity, float value) {
        entity.setPitch(value);
    }

    public static float prevRotationYaw(Entity entity) {
        return entity.lastYaw;
    }

    public static float prevRotationPitch(Entity entity) {
        return entity.lastPitch;
    }

    public static void prevRotationYaw(Entity entity, float value) {
        entity.lastYaw = value;
    }

    public static void prevRotationPitch(Entity entity, float value) {
        entity.lastPitch = value;
    }

    public static float fallDistance(Entity entity) {
        return (float) entity.fallDistance;
    }

    public static void fallDistance(Entity entity, float value) {
        entity.fallDistance = value;
    }

    public static float width(Entity entity) {
        return entity.getWidth();
    }

    public static float height(Entity entity) {
        return entity.getHeight();
    }

    public static boolean isDead(Entity entity) {
        return entity.isRemoved();
    }

    public static int ticksExisted(Entity entity) {
        return entity.age;
    }

    public static boolean isCollidedHorizontally(Entity entity) {
        return entity.horizontalCollision;
    }

    public static boolean isCollidedVertically(Entity entity) {
        return entity.verticalCollision;
    }

    public static boolean isAirBorne(Entity entity) {
        return entity.velocityDirty;
    }

    public static void isAirBorne(Entity entity, boolean value) {
        entity.velocityDirty = value;
    }

    /* A cobweb is what slows an entity by a movement multiplier on 1.21; 1.8 called that being in a web. */
    public static boolean isInWeb(Entity entity) {
        return entity.movementMultiplier.lengthSquared() > 1.0E-7;
    }

    public static int hurtResistantTime(Entity entity) {
        return entity.timeUntilRegen;
    }

    public static void hurtResistantTime(Entity entity, int ticks) {
        entity.timeUntilRegen = ticks;
    }

    public static Entity ridingEntity(Entity entity) {
        return entity.getVehicle();
    }

    public static World worldObj(Entity entity) {
        return entity.getEntityWorld();
    }

    public static float stepHeight(Entity entity) {
        return entity.getStepHeight();
    }

    /* Step height is an attribute on 1.21, which only living entities carry. */
    public static void stepHeight(Entity entity, float value) {
        if (entity instanceof LivingEntity living) {
            EntityAttributeInstance attribute = living.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (attribute != null) {
                attribute.setBaseValue(value);
            }
        }
    }

    /* 1.21 decides frustum skipping in the entity's renderer; nothing on the entity opts out. */
    public static boolean ignoreFrustumCheck(Entity entity) {
        return false;
    }

    public static void ticksExisted(Entity entity, int age) {
        entity.age = age;
    }

    public static int getEntityId(Entity entity) {
        return entity.getId();
    }

    public static void setEntityId(Entity entity, int id) {
        entity.setId(id);
    }

    public static Box getEntityBoundingBox(Entity entity) {
        return entity.getBoundingBox();
    }

    public static float getEyeHeight(Entity entity) {
        return entity.getStandingEyeHeight();
    }

    public static Vec3d getPositionEyes(Entity entity, float partialTicks) {
        return entity.getCameraPosVec(partialTicks);
    }

    public static Vec3d getLook(Entity entity, float partialTicks) {
        return entity.getRotationVec(partialTicks);
    }

    public static UUID getUniqueID(Entity entity) {
        return entity.getUuid();
    }

    public static String getName(Entity entity) {
        return entity.getName().getString();
    }

    public static float getDistanceToEntity(Entity entity, Entity other) {
        return entity.distanceTo(other);
    }

    public static double getDistanceSqToEntity(Entity entity, Entity other) {
        return entity.squaredDistanceTo(other);
    }

    public static double getDistance(Entity entity, double x, double y, double z) {
        return Math.sqrt(entity.squaredDistanceTo(x, y, z));
    }

    public static double getDistanceSq(Entity entity, BlockPos pos) {
        return pos.getSquaredDistance(entity.getX(), entity.getY(), entity.getZ());
    }

    public static boolean isBurning(Entity entity) {
        return entity.isOnFire();
    }

    /* 1.8 asked which material covered the eyes; on 1.21 only the fluids still answer that. */
    public static boolean isInsideOfMaterial(Entity entity, Material material) {
        if (material == Material.water) {
            return entity.isSubmergedInWater();
        }
        return material == Material.lava && entity.isSubmergedIn(FluidTags.LAVA);
    }

    public static boolean isInWater(Entity entity) {
        return entity.isTouchingWater();
    }

    public static boolean isRiding(Entity entity) {
        return entity.hasVehicle();
    }

    public static boolean canBeCollidedWith(Entity entity) {
        return entity.canHit();
    }

    /* 1.8 kept where the server last put an entity in 32nds of a block. */
    public static int serverPosX(Entity entity) {
        return (int) Math.round(entity.getTrackedPosition().withDelta(0, 0, 0).x * 32.0);
    }

    public static int serverPosY(Entity entity) {
        return (int) Math.round(entity.getTrackedPosition().withDelta(0, 0, 0).y * 32.0);
    }

    public static int serverPosZ(Entity entity) {
        return (int) Math.round(entity.getTrackedPosition().withDelta(0, 0, 0).z * 32.0);
    }

    public static float getCollisionBorderSize(Entity entity) {
        return entity.getTargetingMargin();
    }

    public static void copyLocationAndAnglesFrom(Entity entity, Entity other) {
        entity.copyPositionAndRotation(other);
    }

    public static void moveEntity(Entity entity, double x, double y, double z) {
        entity.move(MovementType.SELF, new Vec3d(x, y, z));
    }

    public static HitResult rayTrace(Entity entity, double distance, float partialTicks) {
        return entity.raycast(distance, partialTicks, false);
    }

    /* 1.8 counts the pitch delta upward, 1.21 downward. */
    public static void setAngles(Entity entity, float yaw, float pitch) {
        entity.changeLookDirection(yaw, -pitch);
    }

    public static void setPositionAndRotation(Entity entity, double x, double y, double z, float yaw, float pitch) {
        entity.updatePositionAndAngles(x, y, z, yaw, pitch);
    }

    public static boolean isInvisibleToPlayer(Entity entity, PlayerEntity player) {
        return entity.isInvisibleTo(player);
    }
}
