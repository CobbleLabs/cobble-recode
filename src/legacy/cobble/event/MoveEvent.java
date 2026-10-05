package cobble.event;

/** The distance the local player is about to move this tick; what the listeners leave is what moveEntity gets. */
public final class MoveEvent extends Event {
    private double x;
    private double y;
    private double z;

    public MoveEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return x;
    }

    public void setX(double value) {
        x = value;
    }

    public double getY() {
        return y;
    }

    public void setY(double value) {
        y = value;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double value) {
        z = value;
    }
}
