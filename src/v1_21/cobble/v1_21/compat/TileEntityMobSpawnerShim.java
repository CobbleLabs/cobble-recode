package cobble.v1_21.compat;

import net.minecraft.block.entity.MobSpawnerBlockEntity;

/**
 * 1.8.9's TileEntityMobSpawner on 1.21.11's. Its logic stands for the block
 * entity itself here, as 1.21's needs the world and position to say what it
 * spawns.
 */
public final class TileEntityMobSpawnerShim {
    private TileEntityMobSpawnerShim() {}

    public static MobSpawnerBlockEntity getSpawnerBaseLogic(MobSpawnerBlockEntity spawner) {
        return spawner;
    }
}
