package cobble.module.combat;

import cobble.boot.GameVersion;
import cobble.core.Rng;
import cobble.core.Timer;
import cobble.core.Vec3;
import cobble.game.CrosshairTarget;
import cobble.game.Face;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;

/**
 * On its bind it places a respawn anchor from the hotbar
 * against the block you look at, charges it with glowstone and sets it off
 * with something else in hand, a totem if one is there, then switches itself
 * off. On place it does the charging and the blast for an anchor you put down
 * yourself.
 *
 * <p>Each step is a use on a block. With the aim assist on, the view is held
 * on the centre of the face that step needs, and the use only goes out once
 * the rotation the server is about to get lands on that block, through the
 * game's own use path, the way a player's click would. Without it, the use
 * is sent at the face straight away, which a server checking where the
 * player looks may refuse.
 */
public final class AutoAnchor extends Module {
    private static final int BIND = 0;
    /* Long enough to aim, and for the server to confirm a placement and a charge at a few hundred ms of ping. */
    private static final int GIVE_UP_TICKS = 100;
    private static final double REACH = 4.5;
    private static final int AIM_PRIORITY = 60;

    private enum Step { PLACE, CHARGE, DETONATE }

    private final Setting mode = addChoice("Mode",
            "On bind places, charges and sets one off when you press the key; on place finishes the ones you put down",
            List.of("On bind", "On place"), BIND);
    private final Setting charges = addNumber("Charges", "Glowstone to put in before setting it off", 4, 1, 4, 0);
    private final Setting aimAssist = addBoolean("Aim assist", "Hold the view on the anchor so no use misses", true);
    private final Setting silentAim = addBoolean("Silent aim", "Turn only the rotation the server sees", false)
            .visibleWhen(() -> this.aimAssist.bool());
    private final Setting aimSpeed = addNumber("Aim speed", "Degrees the view turns each tick", 25, 5, 90, 0)
            .visibleWhen(() -> this.aimAssist.bool());
    private final Setting delayMin = addNumber("Delay min", "Shortest wait between steps, in ms", 50, 0, 500, 0);
    private final Setting delayMax = addNumber("Delay max", "Longest wait between steps, in ms", 100, 0, 500, 0);
    private final Setting swapBack = addBoolean("Swap back", "Hand back the slot you had once it goes off", true);

    private final Rng rng = new Rng();
    private final Timer timer = new Timer();
    private long delay;
    private int previousSlot = -1;

    private Step step;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private Face anchorFace;
    private int ticks;

    public AutoAnchor() {
        super("AutoAnchor", "Places, charges and sets off a respawn anchor", Category.COMBAT, Subcategory.ADVANCED);
    }

    @Override
    public boolean supported() {
        return GameVersion.current().atLeast(GameVersion.V1_21_11);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        previousSlot = -1;
        ticks = 0;
        rearm();
        if (mode.choice() == BIND && !begin(Game.get())) {
            setEnabled(false, context);
        }
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            restore(Game.get());
        }
        previousSlot = -1;
    }

    /* An anchor already under the crosshair is finished; otherwise one is placed against what is there. */
    private boolean begin(Game game) {
        CrosshairTarget target = game.crosshair();
        if (target.kind() != CrosshairTarget.Kind.BLOCK) {
            return false;
        }
        anchorFace = target.face();
        if (game.anchorCharges(target.x(), target.y(), target.z()) >= 0) {
            anchorX = target.x();
            anchorY = target.y();
            anchorZ = target.z();
            step = Step.CHARGE;
            return true;
        }
        anchorX = target.x() + anchorFace.dx;
        anchorY = target.y() + anchorFace.dy;
        anchorZ = target.z() + anchorFace.dz;
        step = Step.PLACE;
        return game.findSlot(ItemKind.RESPAWN_ANCHOR, 0, 8) >= 0;
    }

    private void rearm() {
        timer.reset();
        int low = (int) Math.min(delayMin.number(), delayMax.number());
        int high = (int) Math.max(delayMin.number(), delayMax.number());
        delay = high <= low ? low : rng.range(low, high);
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            return;
        }
        if (mode.choice() == BIND) {
            if (++ticks > GIVE_UP_TICKS || !bindStep(state, Game.get())) {
                setEnabled(false, context);
            }
        } else {
            placeStep(state, Game.get());
        }
    }

    /** False once the anchor has gone off, or cannot be. */
    private boolean bindStep(FrameState state, Game game) {
        int charged = game.anchorCharges(anchorX, anchorY, anchorZ);
        switch (step) {
            case PLACE -> {
                if (charged >= 0) {
                    step = Step.CHARGE;
                } else {
                    act(state, game, game.findSlot(ItemKind.RESPAWN_ANCHOR, 0, 8),
                            anchorX - anchorFace.dx, anchorY - anchorFace.dy, anchorZ - anchorFace.dz, anchorFace);
                }
            }
            case CHARGE -> {
                if (charged >= charges.intValue()) {
                    step = Step.DETONATE;
                } else if (charged >= 0) {
                    act(state, game, game.findSlot(ItemKind.GLOWSTONE, 0, 8), anchorX, anchorY, anchorZ, null);
                } else {
                    /* The game shows a placement before the server confirms it; one it refused is gone again. */
                    step = Step.PLACE;
                }
            }
            case DETONATE -> {
                if (charged <= 0) {
                    return false;
                }
                act(state, game, detonatorSlot(game), anchorX, anchorY, anchorZ, null);
            }
        }
        return true;
    }

    private void placeStep(FrameState state, Game game) {
        CrosshairTarget target = game.crosshair();
        int charged = target.kind() == CrosshairTarget.Kind.BLOCK
                ? game.anchorCharges(target.x(), target.y(), target.z()) : -1;
        if (charged < 0) {
            restore(game);
            return;
        }
        anchorFace = target.face();
        int slot = charged < charges.intValue() ? game.findSlot(ItemKind.GLOWSTONE, 0, 8) : detonatorSlot(game);
        act(state, game, slot, target.x(), target.y(), target.z(), null);
    }

    /**
     * One step: the slot in hand, then a use on the block, on {@code face} if
     * the step needs that one. The aim is held every tick; the switch and the
     * use each wait out the delay.
     */
    private void act(FrameState state, Game game, int slot, int x, int y, int z, Face face) {
        if (slot < 0) {
            return;
        }
        Face aimFace = face != null ? face : anchorFace;
        Vec3 centre = new Vec3(x + 0.5 + aimFace.dx * 0.5, y + 0.5 + aimFace.dy * 0.5, z + 0.5 + aimFace.dz * 0.5);
        if (aimAssist.bool()) {
            RotationTarget aim = RotationEngine.aimAt(state.player.eyePos, centre, RotationEngine.pendingYaw());
            aim.speed = aimSpeed.number();
            aim.silent = silentAim.bool();
            aim.priority = AIM_PRIORITY;
            RotationEngine.submit(aim);
        }
        if (!timer.elapsed(delay)) {
            return;
        }
        if (game.selectedSlot() != slot) {
            if (previousSlot < 0) {
                previousSlot = game.selectedSlot();
            }
            game.setSelectedSlot(slot);
            rearm();
            return;
        }
        if (!aimAssist.bool()) {
            game.placeBlock(x, y, z, aimFace, centre, true);
            rearm();
            return;
        }
        /* The rotation this tick's movement packet carries is what the server checks the use against. */
        Raycast.BlockHit hit = Raycast.raycastBlocks(state.player.eyePos,
                Raycast.lookVector(RotationEngine.pendingYaw(), RotationEngine.pendingPitch()), REACH);
        if (hit == null || hit.x() != x || hit.y() != y || hit.z() != z || face != null && hit.face() != face) {
            return;
        }
        /* The use goes through the game's own path, from a crosshair set to the ray just cast:
         * the camera's own pick is a tick old, and with a silent aim it never moved at all. */
        game.setCrosshairTarget(hit.x(), hit.y(), hit.z(), hit.face(), hit.point());
        game.setItemUseCooldown(0);
        game.queueClick(true);
        rearm();
    }

    private void restore(Game game) {
        if (previousSlot >= 0) {
            if (swapBack.bool()) {
                game.setSelectedSlot(previousSlot);
            }
            previousSlot = -1;
        }
    }

    /* A totem first, since the blast is what it is for; otherwise the slot held before, if it was not glowstone. */
    private int detonatorSlot(Game game) {
        int totem = game.findSlot(ItemKind.TOTEM, 0, 8);
        if (totem >= 0) {
            return totem;
        }
        if (previousSlot >= 0 && game.findSlot(ItemKind.GLOWSTONE, previousSlot, previousSlot) < 0) {
            return previousSlot;
        }
        for (int slot = 0; slot <= 8; ++slot) {
            if (game.findSlot(ItemKind.GLOWSTONE, slot, slot) < 0) {
                return slot;
            }
        }
        return -1;
    }
}
