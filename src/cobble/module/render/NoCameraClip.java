package cobble.module.render;

import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Subcategory;

public class NoCameraClip extends Module {
    public NoCameraClip() {
        super("NoCameraClip", "Keeps the third person camera out of walls", Category.VISUALS, Subcategory.BASIC);
    }
}
