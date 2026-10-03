package cobble.module.combat;

import cobble.module.Category;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

/** Widens other entities' hitboxes for the crosshair, so a hit just off the edge still lands. */
public final class HitBoxes extends Module {
    private final Setting expand = addNumber("Expand amount", "Blocks added to every side of a hitbox", 0.35f, 0.0f,
            1.0f, 2);

    public HitBoxes() {
        super("HitBoxes", "Expands entities' hitboxes", Category.COMBAT, Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return String.format("%.2f", expand.number());
    }

    /** What to add to an entity's collision border, for the hook on getCollisionBorderSize. */
    public static float expansion(Entity entity) {
        HitBoxes module = ModuleManager.get(HitBoxes.class);
        if (module == null || !module.enabled() || !(entity instanceof EntityLivingBase)
                || entity == Minecraft.getMinecraft().thePlayer) {
            return 0.0f;
        }
        return module.expand.number();
    }
}
