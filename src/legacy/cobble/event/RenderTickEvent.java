package cobble.event;

public final class RenderTickEvent extends Event implements DrawEvent {
    public final Phase phase;
    public final float partialTicks;

    public RenderTickEvent(Phase phase, float partialTicks) {
        this.phase = phase;
        this.partialTicks = partialTicks;
    }
}
