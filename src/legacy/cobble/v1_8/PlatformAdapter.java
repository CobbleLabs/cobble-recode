package cobble.v1_8;

import cobble.boot.Hook;
import cobble.event.EventBus;
import cobble.game.Game;
import cobble.hook.HookTable;
import cobble.hook.Hooks;
import cobble.legacy.LegacyPlatform;
import cobble.module.minigames.BedwarsSpawnTracker;
import cobble.render.GlCache;
import cobble.util.SkywarsGameState;
import cobble.util.rotation.RotationHandler;
import java.util.List;

/** Lunar's 1.8.9, the version the legacy code is written against. */
public final class PlatformAdapter extends LegacyPlatform {
    private final Game game = new GameAdapter();

    @Override
    public List<Hook> hooks() {
        return HookTable.ALL;
    }

    @Override
    public List<Class<?>> hookHandlers() {
        return List.of(Hooks.class);
    }

    @Override
    public Game game() {
        return game;
    }

    @Override
    public void startTrackers() {
        EventBus.register(RotationHandler.getInstance());
        EventBus.register(new BedwarsSpawnTracker());
        EventBus.register(SkywarsGameState.YFaX());
    }

    @Override
    public void resyncGlState() {
        GlCache.resync();
    }
}
