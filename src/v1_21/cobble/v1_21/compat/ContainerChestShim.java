package cobble.v1_21.compat;

import net.minecraft.inventory.Inventory;
import net.minecraft.screen.GenericContainerScreenHandler;

/** 1.8.9's ContainerChest on 1.21.11's GenericContainerScreenHandler. */
public final class ContainerChestShim {
    private ContainerChestShim() {}

    public static Inventory getLowerChestInventory(GenericContainerScreenHandler handler) {
        return handler.getInventory();
    }
}
