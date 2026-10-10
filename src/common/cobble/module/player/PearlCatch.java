package cobble.module.player;

import cobble.boot.GameVersion;
import cobble.core.Maths;
import cobble.core.Vec3;
import cobble.event.Subscribe;
import cobble.event.UseSlotEvent;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.LocalPlayerView;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catches every pearl you throw with a wind charge. A pearl that runs into a
 * wind charge in flight teleports you right there, so you arrive in the air
 * instead of where it would land: thrown upward, that is the height a mace
 * smash wants. Only upward throws can be caught, since a charge flies barely
 * faster than a pearl and needs the pearl's climb to close in.
 *
 * <p>The catch is planned the way the server flies both. A pearl leaves 0.1
 * below the eye and a charge at it, each at 1.5 blocks a tick plus the
 * movement of the last movement packet, its vertical part only off the
 * ground. Every tick the pearl takes 0.03 of gravity and then 1% of drag
 * before it moves, while the charge flies straight. The pearl, the older
 * entity, ticks first and hits the charge only if its step enters the charge's
 * box from outside, the box grown by the tolerance projectiles gain with age.
 * Once the server's spawn of the pearl arrives, its real velocity, random
 * spread included, replaces the prediction.
 *
 * <p>The aim is the one whose step goes deepest into that box, searched around
 * where the charge's reach crosses the pearl's path. The charge goes out with
 * a real click once the camera is there, no sooner than Charge delay after
 * the pearl: both uses then leave at the same point of a client tick, so the
 * server spawns them that many ticks apart.
 */
public final class PearlCatch extends Module {
    private static final double SPEED = 1.5;
    private static final double GRAVITY = 0.03;
    /* The game's drag is the float 0.99, widened. */
    private static final double DRAG = 0.99f;
    private static final double PEARL_DROP = 0.1;
    private static final double CHARGE_SIZE = 0.3125;
    /* The charge's box hangs this far below its position. */
    private static final double CHARGE_SINK = 0.15;
    private static final double MAX_TOLERANCE = 0.3;
    private static final int FLIGHT_TICKS = 120;
    /* How long past Charge delay a catch may take to line up before the pearl is left alone. */
    private static final int WAIT_TICKS = 20;
    /* The search spans this many quarter degrees either side of the crossing. */
    private static final int SEARCH_STEPS = 8;
    private static final float SEARCH_STEP = 0.25f;
    /* A camera this much shallower in the box than planned is still turning. */
    private static final double SETTLE = 0.05;
    /* How far the server's pearl may be from the predicted one, in blocks and in blocks a tick. */
    private static final double SPAWN_MATCH = 0.5;
    private static final int AIM_PRIORITY = 80;
    private static final String PEARL = "ender_pearl";
    private static final String CHARGE = "wind_charge";

    private record Step(Vec3 from, Vec3 to, double tolerance) {}

    private record Aim(float yaw, float pitch, double depth) {}

    private final Setting aimSpeed = addNumber("Aim speed", "How fast the view turns to the charge's throw",
            8, 1, 15, 1);
    private final Setting chargeDelay = addNumber("Charge delay",
            "Fewest ticks between the pearl and the charge; more catches higher, fewer catches more surely",
            1, 1, 10, 0);

    /* The pearl's flight; step i is the one it takes at age i + 1. */
    private final List<Step> steps = new ArrayList<>();
    private final Set<Integer> knownPearls = new HashSet<>();
    private Vec3 spawnPos;
    private Vec3 spawnVelocity;
    private boolean spawnSeen;
    /* Ticks since the pearl left the hand, or -1 with nothing to catch. */
    private int ticks = -1;
    private boolean chargeThrown;
    private Vec3 pos;
    private Vec3 lastPos;
    private int previousSlot = -1;

    public PearlCatch() {
        super("PearlCatch", "Catches your pearls in the air with a wind charge", Category.PLAYER,
                Subcategory.ADVANCED);
    }

    @Override
    public boolean supported() {
        return GameVersion.current().atLeast(GameVersion.V1_21_11);
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            finish(Game.get());
        }
        ticks = -1;
        previousSlot = -1;
        pos = null;
        lastPos = null;
    }

    @Subscribe
    public void onUseSlot(UseSlotEvent event) {
        Game game = Game.get();
        String used = game.stack(event.slot).id();
        if (used.equals(CHARGE)) {
            chargeThrown = true;
            return;
        }
        if (!used.equals(PEARL) || pos == null) {
            return;
        }
        LocalPlayerView player = game.capture(1.0f).player;
        knownPearls.clear();
        for (Game.EntityRef pearl : game.enderPearls()) {
            knownPearls.add(pearl.id());
        }
        spawnPos = player.eyePos.add(0.0, -PEARL_DROP, 0.0);
        spawnVelocity = Raycast.lookVector(player.yaw, player.pitch).mul(SPEED)
                .add(carried(lastPos == null ? Vec3.ZERO : pos.sub(lastPos), player.onGround));
        spawnSeen = false;
        fly(spawnPos, spawnVelocity);
        ticks = 0;
        chargeThrown = false;
        int delay = chargeDelay.intValue();
        if (!plan(player.eyePos.add(player.velocity.mul(delay)), carried(player.velocity, player.onGround), delay)) {
            ticks = -1;
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.player.valid) {
            return;
        }
        lastPos = pos;
        pos = state.player.pos;
        if (ticks < 0) {
            return;
        }
        Game game = Game.get();
        int slot = game.findSlot(ItemKind.WIND_CHARGE, 0, 8);
        if (chargeThrown || slot < 0 || ++ticks > chargeDelay.intValue() + WAIT_TICKS || ticks > steps.size()) {
            finish(game);
            return;
        }
        if (state.screenOpen) {
            return;
        }
        watchSpawn(game);
        Vec3 eye = state.player.eyePos;
        if (ticks >= chargeDelay.intValue()) {
            Vec3 carried = carried(lastPos == null ? Vec3.ZERO : pos.sub(lastPos), state.player.onGround);
            Aim best = search(eye, carried, ticks);
            double depth = depth(eye, charge(RotationEngine.pendingYaw(), RotationEngine.pendingPitch(), carried),
                    ticks);
            if (best != null && depth > 0.0 && depth >= best.depth - SETTLE) {
                if (previousSlot < 0) {
                    previousSlot = game.selectedSlot();
                }
                game.setSelectedSlot(slot);
                game.queueClick(true);
                return;
            }
        }
        int delay = Math.max(ticks + 1, chargeDelay.intValue());
        if (!plan(eye.add(state.player.velocity.mul(delay - ticks)),
                carried(state.player.velocity, state.player.onGround), delay)) {
            finish(game);
        }
    }

    private void finish(Game game) {
        if (previousSlot >= 0) {
            game.setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
        ticks = -1;
    }

    /* What a projectile takes from its thrower's last movement packet. */
    private static Vec3 carried(Vec3 movement, boolean onGround) {
        return new Vec3(movement.x(), onGround ? 0.0 : movement.y(), movement.z());
    }

    private static Vec3 charge(float yaw, float pitch, Vec3 carried) {
        return Raycast.lookVector(yaw, pitch).mul(SPEED).add(carried);
    }

    private void fly(Vec3 from, Vec3 velocity) {
        steps.clear();
        Vec3 at = from;
        Vec3 motion = velocity;
        for (int age = 1; age <= FLIGHT_TICKS; ++age) {
            motion = new Vec3(motion.x() * DRAG, (motion.y() - GRAVITY) * DRAG, motion.z() * DRAG);
            double length = motion.length();
            Raycast.BlockHit hit = Raycast.raycastBlocks(at, motion.mul(1.0 / length), length);
            Vec3 next = hit != null ? hit.point() : at.add(motion);
            steps.add(new Step(at, next, Maths.clamp((age - 2) / 20.0, 0.0, MAX_TOLERANCE)));
            if (hit != null) {
                return;
            }
            at = next;
        }
    }

    /* The client has not ticked a pearl that arrived since the last tick, so it is exactly as the server threw it. */
    private void watchSpawn(Game game) {
        if (spawnSeen) {
            return;
        }
        for (Game.EntityRef pearl : game.enderPearls()) {
            if (knownPearls.add(pearl.id()) && pearl.pos().sub(spawnPos).length() < SPAWN_MATCH
                    && pearl.velocity().sub(spawnVelocity).length() < SPAWN_MATCH) {
                spawnSeen = true;
                fly(pearl.pos(), pearl.velocity());
                return;
            }
        }
    }

    /**
     * Turns toward the deepest catch for a charge thrown from {@code eye}
     * {@code delay} ticks after the pearl. False once the charge's reach never
     * takes in the pearl, which a later throw only makes worse.
     */
    private boolean plan(Vec3 eye, Vec3 carried, int delay) {
        Aim best = search(eye, carried, delay);
        if (best == null) {
            return false;
        }
        if (best.depth > 0.0) {
            RotationTarget aim = new RotationTarget(best.yaw, best.pitch);
            aim.speed = aimSpeed.number() * 12.0f;
            aim.priority = AIM_PRIORITY;
            RotationEngine.submit(aim);
        }
        return true;
    }

    /** The aim around the crossing whose step goes deepest, or null when the charge's reach never crosses the pearl. */
    private Aim search(Vec3 eye, Vec3 carried, int delay) {
        RotationTarget crossing = crossing(eye, carried, delay);
        if (crossing == null) {
            return null;
        }
        Aim best = null;
        for (int yawStep = -SEARCH_STEPS; yawStep <= SEARCH_STEPS; ++yawStep) {
            for (int pitchStep = -SEARCH_STEPS; pitchStep <= SEARCH_STEPS; ++pitchStep) {
                float yaw = crossing.yaw + yawStep * SEARCH_STEP;
                float pitch = Maths.clamp(crossing.pitch + pitchStep * SEARCH_STEP, -90.0f, 90.0f);
                double depth = depth(eye, charge(yaw, pitch, carried), delay);
                if (best == null || depth > best.depth) {
                    best = new Aim(yaw, pitch, depth);
                }
            }
        }
        return best;
    }

    /** The rotation toward where the charge's reach first takes in one of the pearl's steps, or null if it never does. */
    private RotationTarget crossing(Vec3 eye, Vec3 carried, int delay) {
        for (int age = delay + 2; age <= steps.size(); ++age) {
            Step step = steps.get(age - 1);
            int flown = age - delay - 1;
            Vec3 centre = eye.add(carried.mul(flown));
            double reach = SPEED * flown;
            Vec3 offset = step.from.sub(centre);
            double c = offset.lengthSquared() - reach * reach;
            if (c > 0.0) {
                continue;
            }
            /* Where the step leaves the reach, or its end when the charge has already passed it. */
            Vec3 along = step.to.sub(step.from);
            double a = along.lengthSquared();
            double b = 2.0 * (offset.x() * along.x() + offset.y() * along.y() + offset.z() * along.z());
            double s = Math.min(1.0, (-b + Math.sqrt(b * b - 4.0 * a * c)) / (2.0 * a));
            return RotationEngine.aimAt(centre, step.from.add(along.mul(s)), RotationEngine.pendingYaw());
        }
        return null;
    }

    /**
     * How deep the pearl's deepest step goes into the charge's box, grown by
     * the step's tolerance, with the charge thrown {@code delay} ticks after it;
     * negative when no step enters the box. A step starting inside the box
     * does not hit it, so a step counts only as deep as its start lies outside.
     */
    private double depth(Vec3 eye, Vec3 velocity, int delay) {
        double speed = velocity.length();
        Raycast.BlockHit wall = Raycast.raycastBlocks(eye, velocity.mul(1.0 / speed), speed * steps.size());
        double best = Double.NEGATIVE_INFINITY;
        for (int age = delay + 1; age <= steps.size(); ++age) {
            int flown = age - delay - 1;
            if (wall != null && speed * flown > wall.distance()) {
                break;
            }
            Step step = steps.get(age - 1);
            Vec3 charge = eye.add(velocity.mul(flown));
            Vec3 min = charge.add(-CHARGE_SIZE / 2.0, -CHARGE_SINK, -CHARGE_SIZE / 2.0);
            Vec3 max = min.add(CHARGE_SIZE, CHARGE_SIZE, CHARGE_SIZE);
            double start = gap(step.from, min, max);
            double length = step.to.sub(step.from).length();
            /* Along the step the gap shrinks by at most the step's length; past that it cannot reach the box. */
            double reach = start - length > step.tolerance ? start - length : closest(step, min, max);
            best = Math.max(best, Math.min(start - step.tolerance, step.tolerance - reach));
        }
        return best;
    }

    /* The gap is convex along the step, so a ternary search finds its least. */
    private static double closest(Step step, Vec3 min, Vec3 max) {
        Vec3 along = step.to.sub(step.from);
        double low = 0.0;
        double high = 1.0;
        for (int i = 0; i < 30; ++i) {
            double left = low + (high - low) / 3.0;
            double right = high - (high - low) / 3.0;
            if (gap(step.from.add(along.mul(left)), min, max) < gap(step.from.add(along.mul(right)), min, max)) {
                high = right;
            } else {
                low = left;
            }
        }
        return gap(step.from.add(along.mul(low)), min, max);
    }

    /** How far the box would have to grow on every side to take in the point. */
    private static double gap(Vec3 point, Vec3 min, Vec3 max) {
        double x = Math.max(Math.max(min.x() - point.x(), point.x() - max.x()), 0.0);
        double y = Math.max(Math.max(min.y() - point.y(), point.y() - max.y()), 0.0);
        double z = Math.max(Math.max(min.z() - point.z(), point.z() - max.z()), 0.0);
        return Math.max(x, Math.max(y, z));
    }
}
