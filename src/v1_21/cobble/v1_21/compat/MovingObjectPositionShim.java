package cobble.v1_21.compat;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 1.8.9's MovingObjectPosition as 1.21.11's HitResult, which splits into a
 * block and an entity kind; what one kind lacks reads as 1.8 left it, null.
 */
public final class MovingObjectPositionShim {
    private MovingObjectPositionShim() {}

    public static HitResult.Type typeOfHit(HitResult hit) {
        return hit.getType();
    }

    public static Vec3d hitVec(HitResult hit) {
        return hit.getPos();
    }

    public static Direction sideHit(HitResult hit) {
        return hit instanceof BlockHitResult block ? block.getSide() : null;
    }

    public static Entity entityHit(HitResult hit) {
        return hit instanceof EntityHitResult entity ? entity.getEntity() : null;
    }

    public static BlockPos getBlockPos(HitResult hit) {
        return hit instanceof BlockHitResult block ? block.getBlockPos() : null;
    }

    public static HitResult create(Entity entity, Vec3d point) {
        return new EntityHitResult(entity, point);
    }

    public static HitResult create(HitResult.Type type, Vec3d point, Direction side, BlockPos pos) {
        return type == HitResult.Type.MISS ? BlockHitResult.createMissed(point, side, pos)
                : new BlockHitResult(point, side, pos, false);
    }

    public static HitResult create(Vec3d point, Direction side) {
        return new BlockHitResult(point, side, BlockPos.ORIGIN, false);
    }

    public static HitResult create(Vec3d point, Direction side, BlockPos pos) {
        return new BlockHitResult(point, side, pos, false);
    }
}
