package cobble.module.render;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

/**
 * Takes the screen effects of blindness and nausea away. Remove Effects goes
 * further and drops the effects themselves on this side, slowness included,
 * which also gives back its speed and field of view; the server still has them.
 */
public class AntiDebuff extends Module {
    private final Setting removeNausea = addBoolean("Remove Nausea", "No screen wobble", true);
    private final Setting removeBlindness = addBoolean("Remove Blindness", "No blindness fog", true);
    private final Setting removeSlowness = addBoolean("Remove Slowness", "No slowness, with Remove Effects", true);
    private final Setting removeEffects = addBoolean("Remove Effects",
            "Remove the effects themselves, not just how they look; anticheats can tell", false);

    public AntiDebuff() {
        super("AntiDebuff", "Removes the blindness and nausea effects", Category.VISUALS, Subcategory.BASIC);
    }

    public boolean blocksBlindness(Potion potion) {
        return this.enabled() && removeBlindness.bool() && potion == Potion.blindness;
    }

    public boolean blocksConfusion(Potion potion) {
        return this.enabled() && removeNausea.bool() && potion == Potion.confusion;
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !removeEffects.bool()) {
            return;
        }
        if (removeNausea.bool()) {
            player.removePotionEffectClient(Potion.confusion.id);
        }
        if (removeBlindness.bool()) {
            player.removePotionEffectClient(Potion.blindness.id);
        }
        if (removeSlowness.bool()) {
            player.removePotionEffectClient(Potion.moveSlowdown.id);
        }
    }
}
