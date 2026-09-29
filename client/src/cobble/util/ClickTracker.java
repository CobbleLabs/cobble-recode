package cobble.util;

import cobble.event.MouseEvent;
import cobble.event.Subscribe;

public final class ClickTracker {
    private static final RecentEventCounter leftClicks = new RecentEventCounter();
    public static long lastLeftClick;

    @Subscribe
    public void onMouse(MouseEvent var1) {
        if (var1.pressed && var1.button == 0) {
            leftClicks.record(lastLeftClick = System.currentTimeMillis());
        }
    }

    public static int getLeftCps() {
        return leftClicks.pruneAndCount(System::currentTimeMillis);
    }
}
