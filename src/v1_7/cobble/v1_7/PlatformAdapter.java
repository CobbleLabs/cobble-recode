package cobble.v1_7;

import cobble.boot.Hook;
import cobble.event.EventBus;
import cobble.game.Game;
import cobble.legacy.LegacyPlatform;
import cobble.module.minigames.BedwarsSpawnTracker;
import cobble.util.SkywarsGameState;
import cobble.util.rotation.RotationHandler;
import java.util.List;

/**
 * Lunar's 1.7.10. Most of what runs here is the legacy code, rewritten for
 * 1.7.10 at build time; see src/v1_7/retarget.rules.
 */
public class PlatformAdapter extends LegacyPlatform {
    private final Game game = new GameAdapter();

    @Override
    public List<Hook> hooks() {
        return HookTable.ALL;
    }

    @Override
    public List<Class<?>> hookHandlers() {
        return List.of(Hooks.class, cobble.hook.Hooks.class);
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

    /* 1.7.10 keeps no GL state cache of its own, so there is nothing to bring back in line. */
    @Override
    public void resyncGlState() {}
}
