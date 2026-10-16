package cobble.v1_7.compat;

/** 1.8.9's direction enum; 1.7.10 has one by the same name with almost none of this. */
public enum EnumFacing {
    DOWN(1, Axis.Y, 0, -1, 0),
    UP(0, Axis.Y, 0, 1, 0),
    NORTH(3, Axis.Z, 0, 0, -1),
    SOUTH(2, Axis.Z, 0, 0, 1),
    WEST(5, Axis.X, -1, 0, 0),
    EAST(4, Axis.X, 1, 0, 0);

    public enum Axis { X, Y, Z }

    private static final EnumFacing[] VALUES = values();

    private final int opposite;
    private final Axis axis;
    private final Vec3i direction;

    EnumFacing(int opposite, Axis axis, int x, int y, int z) {
        this.opposite = opposite;
        this.axis = axis;
        this.direction = new Vec3i(x, y, z);
    }

    public static EnumFacing getFront(int index) {
        return VALUES[Math.abs(index % VALUES.length)];
    }

    public int getIndex() {
        return ordinal();
    }

    public EnumFacing getOpposite() {
        return VALUES[opposite];
    }

    public Axis getAxis() {
        return axis;
    }

    public Vec3i getDirectionVec() {
        return direction;
    }

    public int getFrontOffsetX() {
        return direction.getX();
    }

    public int getFrontOffsetY() {
        return direction.getY();
    }

    public int getFrontOffsetZ() {
        return direction.getZ();
    }

    public EnumFacing rotateYCCW() {
        return switch (this) {
            case NORTH -> WEST;
            case EAST -> NORTH;
            case SOUTH -> EAST;
            case WEST -> SOUTH;
            default -> throw new IllegalStateException("cannot rotate " + this + " around Y");
        };
    }

    public EnumFacing rotateAround(Axis around) {
        if (axis == around) {
            return this;
        }
        return switch (around) {
            case X -> switch (this) {
                case NORTH -> DOWN;
                case DOWN -> SOUTH;
                case SOUTH -> UP;
                case UP -> NORTH;
                default -> this;
            };
            case Y -> switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
            case Z -> switch (this) {
                case EAST -> DOWN;
                case DOWN -> WEST;
                case WEST -> UP;
                case UP -> EAST;
                default -> this;
            };
        };
    }
}
