package cobble.event;

/**
 * The rotation and sneak state this tick's movement packet will report.
 * Changes go into the packet only; the player is restored after it is sent.
 */
public final class UpdateWalkingPlayerEvent extends Event {
    private float yaw;
    private float pitch;
    private boolean sneaking;

    public UpdateWalkingPlayerEvent(float yaw, float pitch, boolean sneaking) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.sneaking = sneaking;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float value) {
        yaw = value;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float value) {
        pitch = value;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public void setSneaking(boolean value) {
        sneaking = value;
    }
}
