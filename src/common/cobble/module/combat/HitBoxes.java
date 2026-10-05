package cobble.module.combat;

import cobble.module.Category;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;

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

    /** What the border hook adds to a living entity other than the player. */
    public static float expansion() {
        HitBoxes module = ModuleManager.get(HitBoxes.class);
        return module != null && module.enabled() ? module.expand.number() : 0.0f;
    }
}
