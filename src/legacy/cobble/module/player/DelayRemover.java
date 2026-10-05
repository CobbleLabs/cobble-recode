package cobble.module.player;

import cobble.event.Phase;
import cobble.event.Subscribe;
import cobble.event.TickEndEvent;
import cobble.module.Category;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.util.ClientUtils;
import net.minecraft.client.entity.EntityPlayerSP;

public class DelayRemover extends LegacyModule {
    public Setting legacyHitreg;
    public Setting removeJumpTicks;

    public DelayRemover() {
        super("DelayRemover", "Removes the hit and jump delays", Category.PLAYER, Subcategory.BASIC);
        this.legacyHitreg = addBoolean("1.7 hitreg", "", true);
        this.removeJumpTicks = addBoolean("Remove jump ticks", "", false);
    }

    @Subscribe
    public void onTickEnd(TickEndEvent var1) {
        if (mc.inGameHasFocus && ClientUtils.isInWorld()) {
            if (var1.phase == Phase.END && this.legacyHitreg.bool()) {
                mc.leftClickCounter = 0;
            }

            if (var1.phase == Phase.START && this.shouldRemoveJumpTicks(mc.thePlayer)) {
                mc.thePlayer.jumpTicks = 0;
            }
        }
    }

    public boolean shouldRemoveJumpTicks(EntityPlayerSP var1) {
        return this.enabled() && this.removeJumpTicks.bool() && var1 != null && (!var1.onGround || var1.movementInput == null || !var1.movementInput.jump);
    }
}
