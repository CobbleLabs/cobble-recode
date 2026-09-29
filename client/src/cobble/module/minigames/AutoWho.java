package cobble.module.minigames;

import cobble.core.Log;
import cobble.event.ChatReceivedEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.minigames.autowho.DelayedStateTrigger;
import cobble.util.ClientUtils;

public class AutoWho extends Module {
    private final DelayedStateTrigger delayedStateTrigger = new DelayedStateTrigger();
    private final Setting hideResponse;
    private final Setting delay;

    public AutoWho() {
        super("AutoWho", "Automatically execute /who", Category.MINIGAMES);
        this.delay = addNumber("Delay", "", 0.0F, 0.0F, 5000.0F, 0);
        this.hideResponse = addBoolean("Hide response", "", false);
    }

    @Override
    public void onDisable(Context context) {
        this.delayedStateTrigger.reset();
    }

    @Override
    public void onUpdate() {
        if (!ClientUtils.isInWorld()) {
            this.delayedStateTrigger.reset();
        } else {
            long var1 = System.currentTimeMillis();
            if (this.delayedStateTrigger.shouldFire(this.Vhjyk(), var1, (long)this.delay.number())) {
                mc.thePlayer.sendChatMessage("/who");
            }
        }
    }

    @Subscribe
    public void onChatReceived(ChatReceivedEvent var1) {
        if (var1.messageType != 2 && ClientUtils.isInWorld()) {
            String var2 = ClientUtils.AOAtn(var1.component.getUnformattedText());
            if (this.hideResponse.bool() && var2.startsWith("ONLINE: ")) {
                var1.setCancelled(true);
                Log.info("AutoWho", "[CHAT] %s", var2);
            }
        }
    }

    private int Vhjyk() {
        int var1 = ClientUtils.getBedWarsBoardType();
        if (var1 != -1) {
            return var1;
        } else {
            int var2 = ClientUtils.getSkyWarsBoardType();
            return var2 == -1 ? 0 : var2;
        }
    }
}
