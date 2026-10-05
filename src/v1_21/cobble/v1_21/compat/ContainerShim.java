package cobble.v1_21.compat;

import java.util.List;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/** 1.8.9's Container on 1.21.11's ScreenHandler. */
public final class ContainerShim {
    private ContainerShim() {}

    public static List<Slot> inventorySlots(ScreenHandler handler) {
        return handler.slots;
    }

    public static int windowId(ScreenHandler handler) {
        return handler.syncId;
    }
}
