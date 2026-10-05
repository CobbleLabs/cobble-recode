package cobble.module.player;

import cobble.core.Maths;
import cobble.core.Timer;
import cobble.core.Vec3;
import cobble.game.EntityView;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows an enemy's pearl with one of your own, thrown to
 * where theirs will land. On bind it looks for a pearl already in the air
 * when you press the key; Aggro answers each one as it is thrown. The view
 * turns for real, since 1.21's use packet carries the rotation it was thrown
 * with.
 */
public final class AutoPearl extends Module {
    private static final int ON_BIND = 0;
    /* A pearl belongs to the player nearest it when it first shows up, within this. */
    private static final double OWNER_RANGE = 10.0;
    private static final double SPEED = 1.5;
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.03;
    private static final int FLIGHT_TICKS = 300;
    /* How close our throw's path has to pass the landing point. */
    private static final double MISS = 1.5;
    private static final float ON_TARGET = 0.5f;
    private static final long SETTLE_MS = 100;
    private static final long GIVE_UP_MS = 5000;

    private enum Step { AIM, THROW, RESTORE }

    private record Throw(float yaw, float pitch) {}

    private final Setting mode = addChoice("Mode",
            "On bind follows a pearl in the air when you press the key; Aggro follows each one as it is thrown",
            List.of("On bind", "Aggro"), ON_BIND);
    private final Setting aimSpeed = addNumber("Aim speed", "How fast the view turns to the throw", 5, 1, 15, 1);
    private final Setting angleLimit = addNumber("Angle limit", "Widest angle from your crosshair to follow a pearl to",
            180, 30, 360, 0);
    private final Setting distanceLimit = addNumber("Distance limit", "Closest a pearl may land to you and be followed",
            6, 0, 10, 1);
    private final Setting minHealth = addNumber("Min health", "Least health to throw with", 5, 1, 20, 0);
    private final Setting verticalCheck = addBoolean("Vertical check",
            "Leave pearls landing more than 7 blocks above you", false);
    private final Setting cooldown = addNumber("Pearl cooldown", "Seconds between your throws", 1, 0, 15, 1);
    private final Setting limitToItems = addBoolean("Limit to items",
            "Only while holding a sword, a pearl or nothing", true);

    /* Pearl ids to the id of the player who threw them, or NOBODY for ours and those no one was near. */
    private static final int NOBODY = -1;
    private final Map<Integer, Integer> owners = new HashMap<>();
    private final Set<Integer> followed = new HashSet<>();
    private final Timer sinceThrow = new Timer();
    private final Timer stepTimer = new Timer();
    private boolean thrownOnce;
    private Throw aim;
    private Step step;
    private int previousSlot = -1;

    public AutoPearl() {
        super("AutoPearl", "Throws a pearl after an enemy's", Category.PLAYER, Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        reset(Game.get());
    }

    @Override
    public void onDisable(Context context) {
        if (context.state.player.valid) {
            reset(Game.get());
        }
        owners.clear();
        followed.clear();
    }

    private void reset(Game game) {
        if (previousSlot >= 0) {
            game.setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
        aim = null;
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            return;
        }
        Game game = Game.get();
        List<Game.EntityRef> pearls = game.enderPearls();
        Set<Integer> fresh = track(state, pearls);
        if (aim == null) {
            pick(state, game, pearls, fresh);
            if (aim == null && mode.choice() == ON_BIND) {
                setEnabled(false, context);
            }
            return;
        }
        if (stepTimer.elapsed(GIVE_UP_MS) || !usable(state, game)) {
            finish(game, context);
            return;
        }
        RotationTarget target = new RotationTarget(aim.yaw, aim.pitch);
        target.speed = aimSpeed.number() * 12.0f;
        RotationEngine.submit(target);
        switch (step) {
            case AIM -> {
                if (Math.abs(Maths.angleDifference(state.player.yaw, aim.yaw)) < ON_TARGET
                        && Math.abs(state.player.pitch - aim.pitch) < ON_TARGET && stepTimer.elapsed(SETTLE_MS)) {
                    previousSlot = game.selectedSlot();
                    game.setSelectedSlot(game.findSlot(ItemKind.ENDER_PEARL, 0, 8));
                    step = Step.THROW;
                }
            }
            case THROW -> {
                game.queueClick(true);
                sinceThrow.reset();
                thrownOnce = true;
                step = Step.RESTORE;
            }
            case RESTORE -> finish(game, context);
        }
    }

    private void finish(Game game, Context context) {
        reset(game);
        if (mode.choice() == ON_BIND) {
            setEnabled(false, context);
        }
    }

    /** Learns who threw each new pearl and forgets the ones gone; returns the new ones. */
    private Set<Integer> track(FrameState state, List<Game.EntityRef> pearls) {
        Set<Integer> present = new HashSet<>();
        Set<Integer> fresh = new HashSet<>();
        for (Game.EntityRef pearl : pearls) {
            present.add(pearl.id());
            if (owners.containsKey(pearl.id())) {
                continue;
            }
            double nearest = Math.min(OWNER_RANGE, state.player.pos.sub(pearl.pos()).length());
            int owner = NOBODY;
            for (EntityView entity : state.entities) {
                double distance = entity.pos.sub(pearl.pos()).length();
                if (entity.isPlayer && distance < nearest) {
                    nearest = distance;
                    owner = entity.id;
                }
            }
            if (owner == NOBODY && nearest < OWNER_RANGE) {
                sinceThrow.reset();
                thrownOnce = true;
            }
            owners.put(pearl.id(), owner);
            fresh.add(pearl.id());
        }
        owners.keySet().retainAll(present);
        followed.retainAll(present);
        return fresh;
    }

    private void pick(FrameState state, Game game, List<Game.EntityRef> pearls, Set<Integer> fresh) {
        if (!usable(state, game) || thrownOnce && !sinceThrow.elapsed((long) (cooldown.number() * 1000.0f))) {
            return;
        }
        /* The latest throw first. */
        for (int index = pearls.size() - 1; index >= 0; --index) {
            Game.EntityRef pearl = pearls.get(index);
            int owner = owners.get(pearl.id());
            if (owner == NOBODY || followed.contains(pearl.id())
                    || mode.choice() != ON_BIND && !fresh.contains(pearl.id())) {
                continue;
            }
            Vec3 landing = landing(pearl.pos(), pearl.velocity());
            Throw solution = landing == null ? null : solve(state, landing);
            if (solution != null && worthFollowing(state, landing, owner)) {
                followed.add(pearl.id());
                aim = solution;
                step = Step.AIM;
                stepTimer.reset();
                return;
            }
        }
    }

    private boolean usable(FrameState state, Game game) {
        return state.player.health >= minHealth.number() && game.findSlot(ItemKind.ENDER_PEARL, 0, 8) >= 0
                && (!limitToItems.bool() || game.handEmpty() || game.holdingSword()
                || game.holdingItem(ItemKind.ENDER_PEARL));
    }

    /* Somewhere past its thrower, which is where a chase goes, and in front of us. */
    private boolean worthFollowing(FrameState state, Vec3 landing, int owner) {
        double away = landing.sub(state.player.pos).length();
        double thrower = 0.0;
        for (EntityView entity : state.entities) {
            if (entity.id == owner) {
                thrower = entity.pos.sub(state.player.pos).length();
            }
        }
        float yaw = RotationEngine.aimAt(state.player.eyePos, landing, state.player.yaw).yaw;
        return away > distanceLimit.number() && away > thrower
                && Math.abs(Maths.angleDifference(state.player.yaw, yaw)) <= angleLimit.number() / 2.0f
                && (!verticalCheck.bool() || landing.y() - state.player.pos.y() < 7.0);
    }

    /** Where a pearl comes down: the first block its path meets. */
    private static Vec3 landing(Vec3 pos, Vec3 motion) {
        for (int tick = 0; tick < FLIGHT_TICKS && pos.y() > -128.0; ++tick) {
            double length = motion.length();
            if (length > 1.0E-6) {
                Raycast.BlockHit hit = Raycast.raycastBlocks(pos, motion.mul(1.0 / length), length);
                if (hit != null) {
                    return hit.point();
                }
            }
            pos = pos.add(motion);
            motion = new Vec3(motion.x() * DRAG, motion.y() * DRAG - GRAVITY, motion.z() * DRAG);
        }
        return null;
    }

    /**
     * The rotation whose throw passes nearest the landing point: the yaw
     * straight at it, the pitch found by flying the pearl for each degree,
     * then each tenth around the best, the flatter arc winning a tie.
     */
    private static Throw solve(FrameState state, Vec3 landing) {
        float yaw = RotationEngine.aimAt(state.player.eyePos, landing, state.player.yaw).yaw;
        double radians = Math.toRadians(yaw);
        Vec3 origin = state.player.eyePos.add(-Math.cos(radians) * 0.16, -0.1, -Math.sin(radians) * 0.16);
        float best = Float.NaN;
        double bestMiss = Double.MAX_VALUE;
        for (float pitch = 89.0f; pitch >= -89.0f; pitch -= 1.0f) {
            double miss = miss(origin, yaw, pitch, landing);
            if (miss < bestMiss) {
                bestMiss = miss;
                best = pitch;
            }
        }
        float coarse = best;
        for (float pitch = coarse - 1.0f; pitch <= coarse + 1.0f; pitch += 0.1f) {
            double miss = miss(origin, yaw, Maths.clamp(pitch, -90.0f, 90.0f), landing);
            if (miss < bestMiss) {
                bestMiss = miss;
                best = Maths.clamp(pitch, -90.0f, 90.0f);
            }
        }
        return bestMiss <= MISS ? new Throw(yaw, best) : null;
    }

    /* Closest the flight passes the point, before it falls below it. */
    private static double miss(Vec3 origin, float yaw, float pitch, Vec3 point) {
        Vec3 pos = origin;
        Vec3 motion = Raycast.lookVector(yaw, pitch).mul(SPEED);
        double closest = Double.MAX_VALUE;
        for (int tick = 0; tick < FLIGHT_TICKS; ++tick) {
            Vec3 next = pos.add(motion);
            closest = Math.min(closest, distanceToSegment(point, pos, next));
            if (next.y() < point.y() && motion.y() < 0.0) {
                break;
            }
            pos = next;
            motion = new Vec3(motion.x() * DRAG, motion.y() * DRAG - GRAVITY, motion.z() * DRAG);
        }
        return closest;
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.sub(from);
        Vec3 offset = point.sub(from);
        double lengthSquared = segment.lengthSquared();
        double along = offset.x() * segment.x() + offset.y() * segment.y() + offset.z() * segment.z();
        double t = lengthSquared == 0.0 ? 0.0 : Maths.clamp(along / lengthSquared, 0.0, 1.0);
        return point.sub(from.add(segment.mul(t))).length();
    }
}
