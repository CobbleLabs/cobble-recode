package cobble.event;

import net.minecraft.util.MovingObjectPosition;

public final class ClickMouseEvent extends Event {
    public final MovingObjectPosition movingObjectPosition;

    public ClickMouseEvent(MovingObjectPosition movingObjectPosition) {
        this.movingObjectPosition = movingObjectPosition;
    }
}
