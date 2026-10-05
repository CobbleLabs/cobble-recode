package cobble.v1_21.legacy;

import cobble.boot.Hook;
import java.util.ArrayList;
import java.util.List;

/** 1.21.11's platform with the legacy code's events raised as well, which the legacy modules need to run here. */
public final class PlatformAdapter extends cobble.v1_21.PlatformAdapter {
    @Override
    public List<Hook> hooks() {
        return LegacyHookTable.over(super.hooks());
    }

    @Override
    public List<Class<?>> hookHandlers() {
        List<Class<?>> handlers = new ArrayList<>(super.hookHandlers());
        handlers.add(LegacyHooks.class);
        return handlers;
    }
}
