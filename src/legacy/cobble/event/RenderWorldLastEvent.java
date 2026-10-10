package cobble.event;

public final class RenderWorldLastEvent extends Event implements DrawEvent {
    public final float partialTicks;

    public RenderWorldLastEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }
}
