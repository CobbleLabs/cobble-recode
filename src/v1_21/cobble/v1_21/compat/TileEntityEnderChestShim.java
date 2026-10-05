package cobble.v1_21.compat;

import net.minecraft.block.entity.EnderChestBlockEntity;

/** 1.8.9's TileEntityEnderChest on 1.21.11's EnderChestBlockEntity. */
public final class TileEntityEnderChestShim {
    private TileEntityEnderChestShim() {}

    public static float lidAngle(EnderChestBlockEntity chest) {
        return chest.getAnimationProgress(1.0f);
    }
}
