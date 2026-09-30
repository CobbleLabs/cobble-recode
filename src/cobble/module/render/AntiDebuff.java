package cobble.module.render;

import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Subcategory;
import net.minecraft.potion.Potion;

public class AntiDebuff extends Module {
    public AntiDebuff() {
        super("AntiDebuff", "Removes the blindness and nausea effects", Category.VISUALS, Subcategory.BASIC);
    }

    public boolean blocksBlindness(Potion var1) {
        return this.enabled() && var1 == Potion.blindness;
    }

    public boolean blocksConfusion(Potion var1) {
        return this.enabled() && var1 == Potion.confusion;
    }
}
