package cobble.v1_21.compat;

import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;

/** 1.8.9's TileEntityChest on 1.21.11's ChestBlockEntity, where a trapped chest is a class of its own. */
public final class TileEntityChestShim {
    private TileEntityChestShim() {}

    public static int getChestType(ChestBlockEntity chest) {
        return chest instanceof TrappedChestBlockEntity ? 1 : 0;
    }

    public static float lidAngle(ChestBlockEntity chest) {
        return chest.getAnimationProgress(1.0f);
    }
}
