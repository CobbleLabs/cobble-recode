package cobble.v1_7.compat;

/** BlockBed's 1.8.9 properties over its 1.7.10 metadata: the low two bits face, the eighth the head. */
public final class Beds {
    public enum EnumPartType { HEAD, FOOT }

    public static final PropertyEnum PART =
            new PropertyEnum(state -> (state.metadata() & 8) != 0 ? EnumPartType.HEAD : EnumPartType.FOOT);
    public static final PropertyDirection FACING = new PropertyDirection(state -> switch (state.metadata() & 3) {
        case 0 -> EnumFacing.SOUTH;
        case 1 -> EnumFacing.WEST;
        case 2 -> EnumFacing.NORTH;
        default -> EnumFacing.EAST;
    });

    private Beds() {}
}
