package cobble.module.combat;

import cobble.core.Color;
import cobble.core.Maths;
import cobble.core.Time;
import cobble.core.Vec3;
import cobble.game.CombatTarget;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.Projection;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.render.Renderer;
import cobble.ui.Theme;
import java.util.List;

/**
 * Raven-b's Displace, cut down to the half that matters.
 *
 * <p>Knockback is dealt along the attacker's yaw, not along the line between
 * the two players. Turning ninety degrees on the tick a hit lands therefore
 * throws the target sideways instead of backwards, and walking forward on that
 * same rotation carries you around them rather than into them. Every other tick
 * is spent turned back, so the movement the server sees is a strafe rather than
 * a player facing a wall.
 *
 * <p>The void finder is what turns that from a nuisance into a kill: rather than
 * a fixed offset, the yaw is chosen to point at the nearest column of nothing,
 * so the knockback goes off the edge. Static scans rings around the target for
 * the closest hole; Dynamic scores whole lanes leading away from them and picks
 * the one with the most air along it and no wall in the way.
 *
 * <p>The original's blink, teammate list and item conditions are gone - the ask
 * was the void finder and simpler settings.
 */
public final class Displace extends Module {
    private static final int DIRECTIONS = 16;
    private static final int STATIC_RINGS = 10;
    private static final double RING_STEP = 0.5;
    private static final int VOID_DEPTH = 10;
    private static final double LANE_STEP = 0.5;
    private static final double LANE_DISTANCE = 6.0;
    private static final double LANE_SIDE = 0.45;
    /* One displace lasts a window of ten ticks before the delay is measured from
     * scratch, which is what keeps a held delay from simply switching the module
     * off against a target that never leaves. */
    private static final int WINDOW_TICKS = 10;
    private static final int DISPLACE_PRIORITY = 60;
    private static final double[] COMPASS_X = new double[DIRECTIONS];
    private static final double[] COMPASS_Z = new double[DIRECTIONS];

    static {
        for (int i = 0; i < DIRECTIONS; ++i) {
            double angle = 2.0 * Math.PI * i / DIRECTIONS;
            COMPASS_X[i] = Math.cos(angle);
            COMPASS_Z[i] = Math.sin(angle);
        }
    }

    private final Setting mode = addChoice("Mode",
            "Static turns by a fixed offset; Dynamic aims down the emptiest lane", List.of("Static", "Dynamic"), 0);
    private final Setting yawOffset = addNumber("Yaw offset", "Degrees to turn on the displace tick", 90, 0, 180, 0)
            .visibleWhen(() -> mode.choice() == 0);
    private final Setting direction = addChoice("Direction", "Which way to turn when there is no void to aim at",
            List.of("Left", "Right"), 0).visibleWhen(() -> mode.choice() == 0);
    private final Setting findVoid = addBoolean("Find void", "Aim at the nearest hole rather than the fixed offset",
            true).visibleWhen(() -> mode.choice() == 0);
    private final Setting delay = addNumber("Delay", "Milliseconds into the window before displacing", 0, 0, 500, 0);
    private final Setting range = addNumber("Range", "How far a target may be", 4.5f, 2, 9, 1);
    private final Setting showDirection = addBoolean("Show direction", "Draw the arrow the knockback follows", true);

    private boolean displaceTick;
    private boolean compensate;
    private boolean left;
    private boolean forwardHeld;
    private boolean strafeHeld;
    /* What the player was holding when the module borrowed the key. A binding
     * is sticky - releasing one they are leaning on would take their movement
     * away until they let go and pressed it again. */
    private boolean forwardWasTheirs;
    private boolean strafeWasTheirs;
    /* Which side was actually pressed. The void scan can pick the other side
     * between the press and the release, and letting go of the key that was
     * never pressed would leave the real one held down. */
    private boolean strafeLeft;
    private int holdTicks;
    private int tick;
    private int windowStart = -1;
    private int windowTargetId = -1;

    /* What the arrow draws, kept between the tick that decides it and the frame
     * that draws it. */
    private boolean arrowValid;
    private float arrowYaw;
    private int arrowTargetId = -1;
    private float arrowFade;

    public Displace() {
        super("Displace", "Turns on the hit so the knockback throws them sideways", Category.COMBAT,
                Subcategory.ADVANCED);
        setIcon(Icon.MOVE_3D);
    }

    @Override
    public String suffix() {
        return (int) delay.number() + "ms";
    }

    @Override
    public void onEnable(Context context) {
        displaceTick = false;
        compensate = false;
        forwardHeld = false;
        strafeHeld = false;
        forwardWasTheirs = false;
        strafeWasTheirs = false;
        tick = 0;
        windowStart = -1;
        windowTargetId = -1;
        arrowValid = false;
        arrowFade = 0.0f;
    }

    @Override
    public void onDisable(Context context) {
        releaseMovement();
        displaceTick = false;
        compensate = false;
        arrowValid = false;
    }

    private void releaseMovement() {
        if (forwardHeld) {
            if (!forwardWasTheirs) {
                Game.setBinding("forward", false);
            }
            forwardHeld = false;
        }
        if (strafeHeld) {
            if (!strafeWasTheirs) {
                Game.setBinding(strafeLeft ? "left" : "right", false);
            }
            strafeHeld = false;
        }
        holdTicks = 0;
    }

    private static float yawFromForward(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(z, x)) - 90.0f;
    }

    private static boolean voidColumn(double x, double y, double z) {
        int cellX = Maths.floor(x);
        int cellZ = Maths.floor(z);
        int top = Maths.floor(y) - 1;
        for (int cellY = top; cellY > top - VOID_DEPTH; --cellY) {
            if (!Raycast.blockPassable(cellX, cellY, cellZ)) {
                return false;
            }
        }
        return true;
    }

    /** Yaw pointing past the target at the nearest air column, or NaN. */
    private float staticVoidYaw(FrameState state, EntityView target) {
        Vec3 feet = target.tickPos;
        Vec3 self = state.player.pos;
        double bestX = 0.0;
        double bestZ = 0.0;
        double bestScore = 0.0;
        boolean found = false;

        /* Nearest ring first, and the whole search stops at the first ring that
         * has anything in it: a hole two blocks away is worth more than a wider
         * one four blocks away, whatever the tie-break says. */
        for (int ring = 1; ring <= STATIC_RINGS && !found; ++ring) {
            double radius = ring * RING_STEP;
            for (int index = 0; index < DIRECTIONS; ++index) {
                double x = feet.x() + COMPASS_X[index] * radius;
                double z = feet.z() + COMPASS_Z[index] * radius;
                if (!voidColumn(x, feet.y(), z)) {
                    continue;
                }
                double dx = x - self.x();
                double dz = z - self.z();
                double score = dx * dx + dz * dz;
                if (!found || score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                    found = true;
                }
            }
        }
        if (!found) {
            return Float.NaN;
        }

        /* Which side of the target the hole is on decides the compensating
         * strafe; the sign of the cross product between "towards them" and
         * "towards it" is exactly that question. */
        double targetDx = feet.x() - self.x();
        double targetDz = feet.z() - self.z();
        left = targetDx * (bestZ - self.z()) - targetDz * (bestX - self.x()) < 0.0;

        /* Aim just past the near edge of their hitbox towards the hole rather
         * than at the hole itself: the knockback is dealt along the yaw, and a
         * yaw that misses them entirely deals none. */
        double dx = bestX - feet.x();
        double dz = bestZ - feet.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            return Float.NaN;
        }
        double radius = Math.min(length, Math.max(0.35, target.width * 0.5 + 0.15));
        double aimX = feet.x() + dx / length * radius;
        double aimZ = feet.z() + dz / length * radius;
        return yawFromForward(aimX - state.player.eyePos.x(), aimZ - state.player.eyePos.z());
    }

    /** Yaw along the lane with the most void in it, or NaN. */
    private float dynamicVoidYaw(FrameState state, EntityView target) {
        Vec3 feet = target.tickPos;
        Vec3 self = state.player.pos;
        double bestScore = 0.0;
        double bestX = 0.0;
        double bestZ = 0.0;

        int steps = (int) (LANE_DISTANCE / LANE_STEP);
        for (int index = 0; index < DIRECTIONS; ++index) {
            double forwardX = COMPASS_X[index];
            double forwardZ = COMPASS_Z[index];
            double sideX = -forwardZ;
            double sideZ = forwardX;
            double score = 0.0;
            int run = 0;

            for (int step = 1; step <= steps; ++step) {
                double along = step * LANE_STEP;
                double centreX = feet.x() + forwardX * along;
                double centreZ = feet.z() + forwardZ * along;
                /* A lane with a wall across it is not a lane. Once the path is
                 * blocked, nothing further along it counts. */
                if (!Raycast.blockPassable(Maths.floor(centreX), Maths.floor(feet.y()), Maths.floor(centreZ))) {
                    break;
                }

                boolean centreVoid = false;
                for (int lane = -1; lane <= 1; ++lane) {
                    double offset = lane * LANE_SIDE;
                    if (!voidColumn(centreX + sideX * offset, feet.y(), centreZ + sideZ * offset)) {
                        continue;
                    }
                    /* Nearer void is worth more, and the middle of the lane is
                     * worth more than its edges - a hole they would be knocked
                     * past is not a hole they fall into. */
                    score += (lane == 0 ? 1.4 : 1.0) * (LANE_DISTANCE + LANE_STEP - along);
                    centreVoid |= lane == 0;
                }
                run = centreVoid ? run + 1 : 0;
                score += run * 2.0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestX = forwardX;
                bestZ = forwardZ;
            }
        }
        if (bestScore <= 0.0) {
            return Float.NaN;
        }

        double targetDx = feet.x() - self.x();
        double targetDz = feet.z() - self.z();
        left = targetDx * (feet.z() + bestZ - self.z()) - targetDz * (feet.x() + bestX - self.x()) < 0.0;
        return yawFromForward(bestX, bestZ);
    }

    private EntityView pickTarget(FrameState state) {
        int published = CombatTarget.current(Time.nowSeconds());
        if (!Input.attackHeld() && published < 0) {
            return null;
        }

        EntityView best = null;
        double bestDistance = range.number();
        for (EntityView entity : state.entities) {
            if (!entity.isPlayer || entity.health <= 0.0f) {
                continue;
            }
            /* Range still applies to the published target. Another module may
             * be swinging at somebody well outside the distance a displace is
             * worth, and turning ninety degrees away from a fight for them is
             * worse than not displacing at all. */
            if (entity.id == published && entity.distance <= range.number()) {
                return entity;
            }
            if (entity.distance <= bestDistance) {
                bestDistance = entity.distance;
                best = entity;
            }
        }
        return best;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            onDisable(context);
            return;
        }

        ++tick;

        /* The forward press and the strafe that answers it each last exactly
         * one tick, so the player's own keys come straight back. */
        if (holdTicks > 0 && --holdTicks == 0) {
            releaseMovement();
        }

        EntityView target = pickTarget(state);
        if (target == null) {
            releaseMovement();
            displaceTick = false;
            compensate = false;
            arrowValid = false;
            windowTargetId = -1;
            return;
        }

        /* The tick after a displace is spent strafing back the way the turn
         * threw you, which is what keeps the net movement a sidestep rather
         * than a wander into the opponent. */
        if (compensate) {
            compensate = false;
            strafeLeft = left;
            String strafe = strafeLeft ? "left" : "right";
            strafeWasTheirs = Game.bindingPressed(strafe);
            Game.setBinding(strafe, true);
            strafeHeld = true;
            holdTicks = 1;
            return;
        }

        float yaw;
        if (mode.choice() == 1) {
            yaw = dynamicVoidYaw(state, target);
        } else {
            yaw = findVoid.bool() ? staticVoidYaw(state, target) : Float.NaN;
            if (Float.isNaN(yaw)) {
                left = direction.choice() == 0;
                float base = RotationEngine.sentYaw();
                yaw = left ? base - yawOffset.number() : base + yawOffset.number();
            }
        }
        if (Float.isNaN(yaw)) {
            arrowValid = false;
            return;
        }

        arrowValid = true;
        arrowYaw = yaw;
        arrowTargetId = target.id;

        /* Every other tick. A rotation held across two ticks is a player facing
         * the wrong way; held across one it is a flick, which is what a hit
         * needs. */
        displaceTick = !displaceTick;
        if (!displaceTick) {
            return;
        }

        if (windowTargetId != target.id || windowStart < 0 || tick - windowStart >= WINDOW_TICKS) {
            windowTargetId = target.id;
            windowStart = tick;
        } else {
            int delayTicks = (int) Math.ceil(delay.number() / 50.0f);
            if (tick - windowStart < delayTicks) {
                displaceTick = false;
                return;
            }
        }

        RotationTarget rotation = new RotationTarget(yaw, RotationEngine.sentPitch());
        rotation.speed = 180.0f;
        rotation.smoothing = 0.0f;
        rotation.silent = true;
        rotation.priority = DISPLACE_PRIORITY;
        RotationEngine.submit(rotation);

        forwardWasTheirs = Game.bindingPressed("forward");
        Game.setBinding("forward", true);
        forwardHeld = true;
        holdTicks = 1;
        compensate = true;
    }

    @Override
    public void onRender(Context context) {
        arrowFade = Maths.approach(arrowFade, arrowValid ? 1.0f : 0.0f, 9.0f, context.deltaTime);
        Projection projection = context.projection;
        if (!showDirection.bool() || arrowFade < 0.02f || !projection.valid()) {
            return;
        }

        EntityView target = null;
        for (EntityView entity : context.state.entities) {
            if (entity.id == arrowTargetId) {
                target = entity;
                break;
            }
        }
        if (target == null) {
            return;
        }

        /* A flat arrow lying in the horizontal plane at chest height, pointing
         * the way the knockback will go. Drawn from projected world points
         * rather than as a screen sprite, so it stays welded to the target and
         * shrinks with distance the way anything in the world does. */
        double yawRadians = Math.toRadians(arrowYaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double sideX = -forwardZ;
        double sideZ = forwardX;
        double centreX = target.pos.x();
        double centreY = target.pos.y() + target.height * 0.5;
        double centreZ = target.pos.z();
        double base = target.width * 0.5 + 0.24;

        double[][] shape = {
            {0.0, -0.08}, {0.74, -0.08}, {0.56, -0.30}, {1.26, 0.0}, {0.56, 0.30}, {0.74, 0.08}, {0.0, 0.08},
        };
        float[] xs = new float[shape.length];
        float[] ys = new float[shape.length];
        for (int index = 0; index < shape.length; ++index) {
            double along = base + shape[index][0];
            double across = shape[index][1];
            Projection.ScreenPoint point = projection.project(new Vec3(centreX + forwardX * along + sideX * across,
                    centreY, centreZ + forwardZ * along + sideZ * across));
            if (point == null) {
                return;
            }
            xs[index] = point.x();
            ys[index] = point.y();
        }

        Renderer gfx = Renderer.instance();
        float alpha = arrowFade * animation();
        Color fill = Theme.accent().withAlpha(0.82f * alpha);
        Color edge = Color.rgb(0x05070A, 0.9f * alpha);

        /* Two fans: the shaft, then the head. face() takes four points, so the
         * shaft is one quad and the head is a quad with a doubled tip. */
        gfx.face(new float[] {xs[0], xs[1], xs[5], xs[6]}, new float[] {ys[0], ys[1], ys[5], ys[6]}, fill);
        gfx.face(new float[] {xs[2], xs[3], xs[3], xs[4]}, new float[] {ys[2], ys[3], ys[3], ys[4]}, fill);
        for (int index = 0; index < shape.length; ++index) {
            int next = (index + 1) % shape.length;
            gfx.line(xs[index], ys[index], xs[next], ys[next], 1.4f, edge);
        }
    }
}
