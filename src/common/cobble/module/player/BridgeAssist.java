package cobble.module.player;

import cobble.core.Maths;
import cobble.core.Rng;
import cobble.core.Vec3;
import cobble.game.Face;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import java.util.List;
import java.util.Locale;

/**
 * Two independent halves, both of which are things a
 * player does with their own hands and neither of which sends a packet of its
 * own:
 *
 * <ul>
 *   <li>The sneak. Minecraft stops a sneaking player at the edge of a block, so
 *       bridging safely means shift on while the feet are over nothing and off
 *       again the moment they are not. The test is run against where the player
 *       will be next tick rather than where they are, because a decision made
 *       on the current position arrives a tick after the step off the edge.
 *   <li>The pre-place. A rotation is chosen that puts the crosshair on the side
 *       face of the block being stood on, which is the face a bridge block goes
 *       against. The click stays the player's own; only the aim is helped.
 * </ul>
 *
 * The delays exist because a sneak that snaps on and off on exact tick
 * boundaries is a square wave nobody's hand produces.
 */
public final class BridgeAssist extends Module {
    /* Player hitbox half width. Everything about an edge is measured from this. */
    private static final double HALF_WIDTH = 0.3;
    private static final int PRE_PLACE_PRIORITY = 40;

    private final Setting mode = addChoice("Mode", "Always assists, or only while you hold sneak yourself",
            List.of("Always", "Hold sneak"), 0);
    private final Setting edgeOffset = addNumber("Edge offset",
            "How far past the edge the feet may go before sneaking", 0, 0, 0.30f, 2);
    private final Setting unsneakDelay = addNumber("Unsneak delay",
            "Milliseconds the sneak is held after it is safe", 50, 50, 300, 0);
    private final Setting sneakOnJump = addNumber("Sneak on jump",
            "Milliseconds of sneak when a jump leaves the ground", 0, 0, 500, 0);
    private final Setting holdingBlocks = addBoolean("Holding blocks", "Only while a placeable block is in hand",
            false);
    private final Setting lookingDown = addBoolean("Looking down", "Only while looking down past seventy degrees",
            false);
    private final Setting notMovingForward = addBoolean("Not moving forward", "Stop assisting while walking forward",
            false);
    private final Setting prePlace = addBoolean("Pre place", "Aim at the side face of the block under you", false);
    private final Setting prePlaceSpeed = addNumber("Pre place speed", "Degrees the aim may travel per tick", 40, 5,
            180, 0).visibleWhen(prePlace::bool);

    private final Rng random = new Rng(0x2B7E1516);
    private boolean sneaking;
    /* Ticks left before the sneak may be dropped. Seeded from the delay sliders
     * with a coin flip on the fractional tick, so the same setting does not
     * produce the same integer every time. */
    private int releaseHold;
    private int jumpHold;
    private boolean wasJumping;
    /* The release countdown is seeded once, on the tick the feet come back over
     * solid ground. Re-seeding it every tick that followed would hold the sneak
     * down forever. */
    private boolean overEdge;

    public BridgeAssist() {
        super("BridgeAssist", "Sneaks at the edge and aims at the face you are bridging off", Category.PLAYER,
                Subcategory.BASIC);
        setIcon(Icon.LAYERS);
    }

    @Override
    public String suffix() {
        return String.format(Locale.ROOT, "%.2f", edgeOffset.number());
    }

    @Override
    public void onEnable(Context context) {
        sneaking = false;
        releaseHold = 0;
        jumpHold = 0;
        wasJumping = false;
        overEdge = false;
    }

    @Override
    public void onDisable(Context context) {
        if (sneaking) {
            Game.get().setBinding("sneak", false);
            sneaking = false;
        }
        releaseHold = 0;
        jumpHold = 0;
    }

    private int ticksFor(float millis) {
        float ticks = millis / 50.0f;
        return (int) ticks + (random.unit() < ticks - (float) Math.floor(ticks) ? 1 : 0);
    }

    private void holdSneak() {
        if (!sneaking) {
            Game.get().setBinding("sneak", true);
            sneaking = true;
        }
    }

    private void releaseSneak(boolean immediately) {
        if (!sneaking) {
            return;
        }
        if (!immediately) {
            if (jumpHold > 0) {
                --jumpHold;
                return;
            }
            if (releaseHold > 0) {
                --releaseHold;
                return;
            }
        }
        /* In "hold sneak" mode the player is leaning on the key themselves, and
         * taking it off them for the ticks they are safe is the point of the
         * mode - the key event that put it down is not repeated, so the write
         * sticks. */
        Game.get().setBinding("sneak", false);
        sneaking = false;
        jumpHold = 0;
        releaseHold = 0;
    }

    /**
     * Chebyshev distance from the predicted feet centre to the nearest ground
     * the hitbox still overlaps, or a negative number when there is no ground
     * under it at all - which is the case the sneak exists for.
     */
    private static double edgeDistance(FrameState state) {
        /* Where the feet land next tick. The velocity Minecraft carries is
         * already a per-tick displacement, so this is one step of the same
         * integration the game is about to run, minus the friction it will
         * apply afterwards. */
        double feetX = state.player.pos.x() + state.player.velocity.x();
        double feetZ = state.player.pos.z() + state.player.velocity.z();
        int groundY = Maths.floor(state.player.pos.y() - 0.01);

        double nearest = -1.0;
        for (int x = Maths.floor(feetX - HALF_WIDTH); x <= Maths.floor(feetX + HALF_WIDTH); ++x) {
            for (int z = Maths.floor(feetZ - HALF_WIDTH); z <= Maths.floor(feetZ + HALF_WIDTH); ++z) {
                if (Raycast.blockPassable(x, groundY, z)) {
                    continue;
                }
                /* Closest point of that cell to the feet, measured per axis and
                 * taken as the larger of the two: a foot hanging over a corner
                 * is as unsupported as one hanging over a side. */
                double closestX = Maths.clamp(feetX, x, x + 1);
                double closestZ = Maths.clamp(feetZ, z, z + 1);
                double distance = Math.max(Math.abs(feetX - closestX), Math.abs(feetZ - closestZ));
                if (nearest < 0.0 || distance < nearest) {
                    nearest = distance;
                }
            }
        }
        return nearest;
    }

    /*
     * Whether the player is asking to sneak. The binding is cleared while the
     * module is running the player unsneaked, and Minecraft only sets it again
     * on a key event - which never comes, because the key never went up. So
     * the key itself is asked too; without it the mode assists exactly once
     * and then switches itself off for as long as the player keeps holding
     * shift, which is the whole time.
     */
    private static boolean wantsSneak() {
        return Game.get().bindingPressed("sneak") || Game.get().bindingPhysicallyHeld("sneak");
    }

    private boolean conditionsMet(FrameState state) {
        if (notMovingForward.bool() && Game.get().bindingPressed("forward")) {
            return false;
        }
        if (lookingDown.bool() && state.player.pitch < 70.0f) {
            return false;
        }
        if (holdingBlocks.bool() && !Game.get().holdingBlock()) {
            return false;
        }
        return mode.choice() != 1 || sneaking || wantsSneak();
    }

    /** Aims at the side face of the support block, the way a bridge is built. */
    private void aimPrePlace(FrameState state) {
        float yaw = RotationEngine.sentYaw();
        Vec3 pos = state.player.pos;
        int feetY = Maths.floor(pos.y()) - 1;
        int minX = Maths.floor(pos.x() - HALF_WIDTH);
        int maxX = Maths.floor(pos.x() + HALF_WIDTH);
        int minZ = Maths.floor(pos.z() - HALF_WIDTH);
        int maxZ = Maths.floor(pos.z() + HALF_WIDTH);

        /* Walking the pitch downwards rather than solving for it: the face the
         * ray lands on is what decides whether a click places anything, and the
         * only honest way to know that is to cast the ray the server will
         * reconstruct. The step is jittered so the search does not always
         * sample the same angles - the winning pitch is otherwise one of thirty
         * fixed values. */
        float bestPitch = 0.0f;
        float bestDelta = Float.MAX_VALUE;
        boolean found = false;

        for (float pitch = 60.0f; pitch <= 90.0f;) {
            pitch += random.range(0.6f, 1.5f);
            float sample = Math.min(pitch, 90.0f);
            Raycast.BlockHit hit = Raycast.raycastBlocks(state.player.eyePos, Raycast.lookVector(yaw, sample), 4.5);
            if (hit == null || hit.face().vertical()) {
                continue;
            }
            /* Only a block the player is standing on, and only where the cell
             * the click would fill is actually empty. */
            if (hit.y() != feetY || hit.x() < minX || hit.x() > maxX || hit.z() < minZ || hit.z() > maxZ) {
                continue;
            }
            if (!Raycast.blockPassable(hit.x() + hit.face().dx, feetY,
                    hit.z() + hit.face().dz)) {
                continue;
            }
            float delta = Math.abs(sample - state.player.pitch);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestPitch = sample;
                found = true;
            }
        }
        if (!found) {
            return;
        }

        /* Never silent, and not as a default that could be changed.
         *
         * A silent rotation is one the server is told about and the camera is
         * not. That is the right trade for something that acts on the world by
         * itself, because the player is not aiming at anything. Pre-place is the
         * opposite: the module places nothing at all, it only moves the aim so
         * that the click the player is about to make lands on the face they are
         * bridging off. An aim they cannot see is one they cannot correct, and
         * it would put their block somewhere they never pointed. So this moves
         * the camera, and at a speed they can follow. */
        RotationTarget target = new RotationTarget(yaw, bestPitch);
        target.speed = prePlaceSpeed.number();
        target.smoothing = 0.35f;
        target.silent = false;
        target.priority = PRE_PLACE_PRIORITY;
        RotationEngine.submit(target);
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            onDisable(context);
            return;
        }
        if (!conditionsMet(state)) {
            releaseSneak(true);
            wasJumping = state.player.jumping;
            return;
        }

        if (prePlace.bool()) {
            aimPrePlace(state);
        }

        /* A jump off a bridge is the one moment the edge test cannot help: the
         * feet leave the block before the arc is decided, so the sneak is held
         * across that window instead, for a rounded number of ticks. */
        boolean jumping = Game.get().bindingPressed("jump");
        if (jumping && !wasJumping && state.player.onGround && sneakOnJump.number() > 0.0f) {
            jumpHold = ticksFor(sneakOnJump.number());
            holdSneak();
            wasJumping = true;
            return;
        }
        wasJumping = jumping;

        double distance = edgeDistance(state);
        if (distance < 0.0) {
            /* Nothing under the feet at all next tick. On the ground that is the
             * step off the edge the sneak exists for; in the air it is a fall
             * already in progress and there is nothing left to hold on to. */
            if (state.player.onGround) {
                overEdge = true;
                holdSneak();
            } else {
                releaseSneak(false);
            }
            return;
        }

        if (distance > edgeOffset.number()) {
            overEdge = true;
            holdSneak();
            return;
        }
        if (overEdge) {
            overEdge = false;
            releaseHold = ticksFor(unsneakDelay.number() - 50.0f);
        }
        releaseSneak(false);
    }
}
