package cobble.v1_21.compat;

import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

/** 1.8.9's MobSpawnerBaseLogic, on the spawner it belongs to; see TileEntityMobSpawnerShim. */
public final class MobSpawnerBaseLogicShim {
    private MobSpawnerBaseLogicShim() {}

    /* 1.8's entity names, "Zombie" or "CaveSpider", from the id rather than the client's language. */
    public static String getEntityNameToSpawn(MobSpawnerBlockEntity spawner) {
        Entity entity = spawner.getLogic().getRenderedEntity(spawner.getWorld(), spawner.getPos());
        if (entity == null) {
            return "";
        }
        StringBuilder name = new StringBuilder();
        for (String word : EntityType.getId(entity.getType()).getPath().split("_")) {
            if (!word.isEmpty()) {
                name.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
        }
        return name.toString();
    }
}
