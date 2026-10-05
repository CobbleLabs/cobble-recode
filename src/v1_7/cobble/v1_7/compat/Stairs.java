package cobble.v1_7.compat;

/** BlockStairs' 1.8.9 properties over its 1.7.10 metadata: the low two bits face, the third the half. */
public final class Stairs {
    public enum EnumHalf { TOP, BOTTOM }

    public static final PropertyEnum HALF =
            new PropertyEnum(state -> (state.metadata() & 4) != 0 ? EnumHalf.TOP : EnumHalf.BOTTOM);
    public static final PropertyDirection FACING = new PropertyDirection(state -> switch (state.metadata() & 3) {
        case 0 -> EnumFacing.EAST;
        case 1 -> EnumFacing.WEST;
        case 2 -> EnumFacing.SOUTH;
        default -> EnumFacing.NORTH;
    });

    private Stairs() {}
}
