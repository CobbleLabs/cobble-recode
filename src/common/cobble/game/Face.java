package cobble.game;

import java.util.Locale;

/** A block face, in the order every version's own direction enum declares them. */
public enum Face {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    private static final Face[] VALUES = values();

    public final int dx;
    public final int dy;
    public final int dz;

    Face(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public static Face byIndex(int index) {
        return VALUES[index];
    }

    /** Null for anything that is not a face name. */
    public static Face byName(String name) {
        for (Face face : VALUES) {
            if (face.id().equals(name)) {
                return face;
            }
        }
        return null;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean vertical() {
        return dy != 0;
    }
}
