package cobble.util.raytrace;

import cobble.module.Module;
import cobble.module.ModuleManager;

/** A module that points the crosshair at the blocks it breaks; while one does, nothing else may turn the player. */
public interface BlockNuker extends PointedObjectOverrider {
    static boolean steering() {
        for (Module module : ModuleManager.all()) {
            if (module instanceof BlockNuker nuker && nuker.shouldOverridePointedObject()) {
                return true;
            }
        }
        return false;
    }
}
