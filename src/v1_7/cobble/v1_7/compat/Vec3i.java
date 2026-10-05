package cobble.v1_7.compat;

/** 1.8.9's integer vector, which 1.7.10 does not have. */
public class Vec3i implements Comparable<Vec3i> {
    private final int x;
    private final int y;
    private final int z;

    public Vec3i(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3i(double x, double y, double z) {
        this((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Vec3i vector && vector.getX() == getX() && vector.getY() == getY()
                && vector.getZ() == getZ();
    }

    @Override
    public int hashCode() {
        return (getY() + getZ() * 31) * 31 + getX();
    }

    @Override
    public int compareTo(Vec3i other) {
        if (getY() != other.getY()) {
            return getY() - other.getY();
        }
        return getZ() == other.getZ() ? getX() - other.getX() : getZ() - other.getZ();
    }
}
