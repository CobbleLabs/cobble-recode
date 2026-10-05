package cobble.module.render;

import cobble.module.Category;
import cobble.module.LegacyModule;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Chams on 1.21.11: other players get the game's own outline, which draws
 * through walls. 1.21 queues entities up and draws them later, out of reach
 * of the polygon offset the 1.8 version sets around each player.
 */
public class Chams extends LegacyModule {
    public Chams() {
        super("Chams", "Draws players through walls", Category.VISUALS, Subcategory.MEDIUM);
    }

    public boolean outlines(Entity entity) {
        return this.enabled() && entity instanceof PlayerEntity && entity != mc.player
                && !AntiBot.shouldHideEntity(entity);
    }
}
