package cobble.module.player;

import cobble.boot.GameVersion;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;

/**
 * On its bind, looks straight down, throws a wind charge
 * from the hotbar at your feet for the jump, then hands back the slot it took
 * and switches itself off. The throw goes the way the camera looks, which is
 * why the aim is a real turn and not a silent one.
 */
public final class WindCharge extends Module {
    private static final float DOWN = 90.0f;
    /* How far short of straight down the throw may leave; past this the charge still lands at your feet. */
    private static final float CLOSE_ENOUGH = 80.0f;
    private static final int GIVE_UP_TICKS = 20;

    private final Setting aimSpeed = addNumber("Aim speed", "How fast the view turns down", 7, 1, 10, 1);

    private int previousSlot = -1;
    private boolean thrown;
    private int ticks;

    public WindCharge() {
        super("WindCharge", "Throws a wind charge at your feet", Category.PLAYER, Subcategory.MEDIUM);
    }

    @Override
    public boolean supported() {
        return GameVersion.current().atLeast(GameVersion.V1_21_11);
    }

    @Override
    public void onEnable(Context context) {
        Game game = Game.get();
        int slot = game.findSlot(ItemKind.WIND_CHARGE, 0, 8);
        if (!context.state.player.valid || !context.state.player.onGround || slot < 0) {
            setEnabled(false, context);
            return;
        }
        previousSlot = game.selectedSlot();
        game.setSelectedSlot(slot);
        thrown = false;
        ticks = 0;
    }

    @Override
    public void onDisable(Context context) {
        if (previousSlot >= 0 && context.state.player.valid) {
            Game.get().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.player.valid || thrown || ++ticks > GIVE_UP_TICKS) {
            setEnabled(false, context);
            return;
        }
        if (state.player.pitch < CLOSE_ENOUGH) {
            RotationTarget down = new RotationTarget(state.player.yaw, DOWN);
            down.speed = aimSpeed.number() * 12.0f;
            RotationEngine.submit(down);
            return;
        }
        Game.get().queueClick(true);
        thrown = true;
    }
}
