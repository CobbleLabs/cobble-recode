package cobble.event;

/** The local player about to use a stack that is off its cooldown, so the use will go through. */
public final class UseSlotEvent extends Event {
    /** Inventory index of the stack: the selected hotbar slot, or 40 for the off hand. */
    public final int slot;

    public UseSlotEvent(int slot) {
        this.slot = slot;
    }
}
