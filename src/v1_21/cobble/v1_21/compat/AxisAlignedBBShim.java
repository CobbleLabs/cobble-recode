package cobble.v1_21.compat;

import java.util.List;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** 1.8.9's AxisAlignedBB on 1.21.11's Box. */
public final class AxisAlignedBBShim {
    private AxisAlignedBBShim() {}

    public static Box addCoord(Box box, double x, double y, double z) {
        return box.stretch(x, y, z);
    }

    /* 1.8 gives the point and the face the segment enters by, or null. */
    public static HitResult calculateIntercept(Box box, Vec3d from, Vec3d to) {
        return Box.raycast(List.of(box), from, to, BlockPos.ORIGIN);
    }

    public static boolean intersectsWith(Box box, Box other) {
        return box.intersects(other);
    }

    public static boolean isVecInside(Box box, Vec3d point) {
        return box.contains(point);
    }
}
