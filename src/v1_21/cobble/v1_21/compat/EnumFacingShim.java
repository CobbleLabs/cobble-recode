package cobble.v1_21.compat;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

/** 1.8.9's EnumFacing on 1.21.11's Direction, which keeps its order and names. */
public final class EnumFacingShim {
    private EnumFacingShim() {}

    public static Vec3i getDirectionVec(Direction direction) {
        return direction.getVector();
    }

    public static Direction getFront(int index) {
        return Direction.byIndex(index);
    }

    public static int getFrontOffsetX(Direction direction) {
        return direction.getOffsetX();
    }

    public static int getFrontOffsetY(Direction direction) {
        return direction.getOffsetY();
    }

    public static int getFrontOffsetZ(Direction direction) {
        return direction.getOffsetZ();
    }

    public static Direction rotateAround(Direction direction, Direction.Axis axis) {
        return direction.rotateClockwise(axis);
    }

    public static Direction rotateYCCW(Direction direction) {
        return direction.rotateYCounterclockwise();
    }
}
