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
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Held down over a drop, it finds a block face within reach, turns to it and
 * clicks - the thing a player does at the bottom of a fall and almost never
 * does in time. Auto Clutch does the same thing unprompted, armed by taking a
 * hit and by a fall prediction that says the ground is far enough away to be
 * worth the block.
 *
 * <p>The aim is not guessed at. Every candidate rotation is cast against the
 * block grid before it is chosen, and the placement only goes out on a tick
 * where the rotation this tick's movement packet carries lands on a face that
 * can be built off - the reason the click survives a reconstruction on the far
 * end.
 *
 * <p>The placement leaves from the tick hook, which runs ahead of the game's
 * input handling and so ahead of this tick's movement packet: the order a real
 * click has, and the one Grim's Post check expects. Sent from the frame instead
 * it lands just behind a movement packet, and held down beside a wall that
 * raised several hundred Post alerts in a couple of minutes against a real
 * GrimAC server.
 *
 * <p>While an aim is live the attack and use bindings are held down for the
 * player and given back on release, which is what stops a held right mouse
 * from placing at the raw crosshair before the confirmed rotation lands.
 */
public final class Clutch extends Module {
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final int CLUTCH_PRIORITY = 80;
    /* The box the original walks: one to four cells under the feet, five out on
     * each side. Anything outside it is out of reach of a clutch anyway. */
    private static final int SEARCH_RADIUS = 5;
    private static final int SEARCH_DEPTH = 4;
    /* Samples per axis across a face, as fractions of a block: 0 to 1 in
     * fifths - six values, corners and centre included - each jittered by a
     * tenth of the step so two consecutive clutches do not click the same
     * pixel of the same face. */
    private static final double SAMPLE_STEP = 0.2;
    private static final double INSET = 0.05;
    /* Rays a single tick's search may spend. The cell list is sorted nearest-first
     * and the first face that works ends it, so this is only ever reached by a
     * tick spent inside a wall - where without it the search would cast a hundred
     * rays at each of a hundred cells. */
    private static final int RAY_BUDGET = 2400;

    private final Setting reach = addNumber("Reach", "How far a face may be to count", 4.5f, 1, 4.5f, 1);
    private final Setting speed = addNumber("Speed", "Degrees the aim may travel per tick", 80, 5, 180, 0);
    private final Setting snapback = addNumber("Snapback speed", "Degrees per tick on the way back", 120, 5, 180,
            0);
    private final Setting maxBlocks = addNumber("Max blocks", "Blocks to spend in one fall; zero is unlimited", 10,
            0, 20, 0);
    private final Setting tolerance = addNumber("Rotation tolerance",
            "Degrees the aim may be from the sent rotation and still place", 25, 5, 100, 0);
    private final Setting predict = addBoolean("Simulate future position",
            "Weight the search towards where the fall is taking you", true);
    private final Setting clickMode = addChoice("Click",
            "Vanilla queues a press the game acts on itself; Direct places through the controller from here",
            List.of("Vanilla", "Direct"), 0);
    private final Setting silent = addBoolean("Silent", "Send the aim without moving the camera", true);
    private final Setting auto = addBoolean("Auto clutch", "Arm on a hit and clutch a long fall by itself", false);
    private final Setting minimumFall = addNumber("Minimum fall distance",
            "Blocks of predicted fall before auto arms", 10, 3, 20, 0).visibleWhen(auto::bool);
    private final Setting bind = addKey("Bind", "Held to clutch by hand", 0);

    private final Rng random = new Rng(0xA5C3D19F);

    /* The live aim, or null when there is none. */
    private RotationTarget aim;
    private int placed;
    private int previousSlot = -1;
    private int plannedSlot = -1;
    private boolean bindingsTaken;
    private int raysLeft;

    private boolean hasLastPlaced;
    private int lastPlacedX;
    private int lastPlacedY;
    private int lastPlacedZ;

    /* Auto clutch arms on a hit and disarms once the player is standing on
     * something that is not about to disappear from under them. The landed
     * guard is what keeps it armed across the bounce: hitting a ledge on the
     * way down is not the same as having landed. */
    private boolean autoArmed;
    private boolean autoChecking;
    private int autoCheckTick;
    private boolean autoLandedGuard;
    private int autoLandedAge;
    private int lastHurtTime = -1;

    private record Candidate(double cost, RotationTarget rotation) {}

    private record Cell(double score, int x, int y, int z) {}

    /*
     * Vanilla free fall, stepped the way Entity.moveEntity steps it: gravity, a
     * sub-stepped per-axis move against the block grid, then drag. Sub-stepping
     * is the whole point - a fall integrated in one jump per tick tunnels
     * straight through the floor at three blocks a tick and reports a drop that
     * never ends.
     */
    private static final class Body {
        private double x;
        private double y;
        private double z;
        private double motionX;
        private double motionY;
        private double motionZ;
        private boolean onGround;

        Body(FrameState state) {
            x = state.player.pos.x();
            y = state.player.pos.y();
            z = state.player.pos.z();
            motionX = state.player.velocity.x();
            motionY = state.player.velocity.y();
            motionZ = state.player.velocity.z();
            onGround = state.player.onGround;
        }

        void step(boolean stopHorizontal) {
            if (stopHorizontal) {
                motionX = 0.0;
                motionZ = 0.0;
            }
            motionY -= 0.08;

            /* Vanilla sweeps the hitbox; this walks it in slices no longer than
             * half a block instead, which is the same answer against full cubes
             * and is the difference between noticing the ground and falling
             * straight through it. */
            double longest = Math.max(Math.abs(motionX), Math.max(Math.abs(motionY), Math.abs(motionZ)));
            int slices = Math.max(1, (int) Math.ceil(longest / 0.45));
            double sliceX = motionX / slices;
            double sliceY = motionY / slices;
            double sliceZ = motionZ / slices;

            boolean hitX = false;
            boolean hitY = false;
            boolean hitZ = false;
            onGround = false;

            for (int slice = 0; slice < slices && !hitY; ++slice) {
                double nextY = y + sliceY;
                if (blockedAt(x, nextY, z)) {
                    hitY = true;
                    if (sliceY < 0.0) {
                        y = Math.floor(nextY) + 1.0;
                        onGround = true;
                    }
                } else {
                    y = nextY;
                }

                if (blockedAt(x + sliceX, y, z)) {
                    hitX = true;
                } else {
                    x += sliceX;
                }

                if (blockedAt(x, y, z + sliceZ)) {
                    hitZ = true;
                } else {
                    z += sliceZ;
                }
            }

            if (hitX) {
                motionX = 0.0;
            }
            if (hitY) {
                motionY = 0.0;
            }
            if (hitZ) {
                motionZ = 0.0;
            }

            motionY *= 0.9800000190734863;
            motionX *= 0.91;
            motionZ *= 0.91;
        }
    }

    public Clutch() {
        super("Clutch", "Turns to a face within reach and blocks the fall", Category.PLAYER, Subcategory.MEDIUM);
        setIcon(Icon.BOX);
    }

    @Override
    public String suffix() {
        return aim != null ? Integer.toString(placed) : "";
    }

    @Override
    public void onEnable(Context context) {
        aim = null;
        placed = 0;
        previousSlot = -1;
        plannedSlot = -1;
        bindingsTaken = false;
        hasLastPlaced = false;
        autoArmed = false;
        autoChecking = false;
        autoCheckTick = 0;
        autoLandedGuard = false;
        autoLandedAge = 0;
        lastHurtTime = -1;
    }

    @Override
    public void onDisable(Context context) {
        giveBindings(context.state);
        restoreSlot(context.state);
        aim = null;
        hasLastPlaced = false;
        autoArmed = false;
        autoChecking = false;
        autoLandedGuard = false;
    }

    private void restoreSlot(FrameState state) {
        if (previousSlot >= 0 && state.player.valid) {
            Game.get().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
        plannedSlot = -1;
    }

    /*
     * Attack and use are held down for the player for as long as an aim is
     * live and hands them back from the real mouse state afterwards. Without it
     * a player leaning on right mouse places at the raw crosshair every tick,
     * which is both the wrong block and a placement the confirmed rotation never
     * covered.
     */
    private void takeBindings() {
        /* Every tick, not once: the player can press the button at any point
         * during the clutch and the game's own key handling sets the binding
         * back. */
        bindingsTaken = true;
        Game.get().setBinding("attack", false);
        Game.get().setBinding("use", false);
    }

    private void giveBindings(FrameState state) {
        if (!bindingsTaken) {
            return;
        }
        bindingsTaken = false;
        if (state.screenOpen) {
            return;
        }
        Game.get().setBinding("attack", Input.attackHeld());
        Game.get().setBinding("use", Input.useHeld());
    }

    /* Squared distance from a point to a cell's box, which is what decides which
     * candidate is worth casting at first and which are out of reach entirely. */
    private static double cellDistance(Vec3 from, int x, int y, int z) {
        double dx = from.x() - Maths.clamp(from.x(), x, x + 1);
        double dy = from.y() - Maths.clamp(from.y(), y, y + 1);
        double dz = from.z() - Maths.clamp(from.z(), z, z + 1);
        return dx * dx + dy * dy + dz * dz;
    }

    /*
     * Whether a block filling this cell would land inside the player.
     *
     * Vanilla asks the same question through World.canBlockBePlaced, which runs
     * the candidate shape against every entity in it. Without it the nearest
     * cell to a standing player is always the one under their feet, its top face
     * is always reachable, and the block it would place always goes in the cell
     * the player is standing in - so the search settles on a placement that can
     * never succeed and never looks at the wall beside it.
     */
    private static boolean intersectsPlayer(FrameState state, int x, int y, int z) {
        Vec3 feet = state.player.pos;
        return x < feet.x() + HALF_WIDTH && x + 1 > feet.x() - HALF_WIDTH
                && y < feet.y() + HEIGHT && y + 1 > feet.y()
                && z < feet.z() + HALF_WIDTH && z + 1 > feet.z() - HALF_WIDTH;
    }

    /* The player's hitbox against the block grid. Every collider the raycast
     * sees is a full cube, so a corner test at each of the four footprint
     * columns over every layer the box spans is the whole of it. */
    private static boolean blockedAt(double x, double y, double z) {
        int lowY = Maths.floor(y);
        int highY = Maths.floor(y + HEIGHT - 0.01);
        for (int layer = lowY; layer <= highY; ++layer) {
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    if (!Raycast.blockPassable(Maths.floor(x + HALF_WIDTH * dx), layer,
                            Maths.floor(z + HALF_WIDTH * dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean willFallFar(FrameState state, double minimum) {
        Body body = new Body(state);
        double startY = body.y;
        for (int tick = 0; tick < 60; ++tick) {
            body.step(false);
            if (body.onGround) {
                return false;
            }
            /* Answered the moment the drop is long enough, not at the end of the
             * simulation: a fall that clears the threshold and lands sixty ticks
             * later is still a fall worth a block. */
            if (startY - body.y > minimum) {
                return true;
            }
        }
        return false;
    }

    private static boolean willFallSoon(FrameState state) {
        Body body = new Body(state);
        for (int tick = 0; tick < 10; ++tick) {
            body.step(true);
            if (!body.onGround && body.motionY < 0.0) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 futurePosition(FrameState state) {
        Body body = new Body(state);
        double startY = body.y;
        for (int tick = 0; tick < 20; ++tick) {
            body.step(false);
            /* Two blocks of drop and no further. The point of the future
             * position is to lean the search towards the side of the box the
             * fall is carrying the player onto, and a point a hundred blocks
             * down is not nearer to any of them - it sorts the whole box by
             * depth and buries the wall that is actually within reach. */
            if (body.y < startY - 2.0 || body.onGround) {
                break;
            }
        }
        return new Vec3(body.x, body.y, body.z);
    }

    private static boolean underPlayer(FrameState state, int cellX, int cellY, int cellZ) {
        Vec3 pos = state.player.pos;
        if (cellY >= Maths.floor(pos.y())) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (cellX == Maths.floor(pos.x() + HALF_WIDTH * dx)
                        && cellZ == Maths.floor(pos.z() + HALF_WIDTH * dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * The first gate, and the line between a clutch and a scaffold: there
     * has to be nothing under the feet. Standing on something, the module would
     * find the side of the block it is standing on and start bridging outwards -
     * placing on every tick from a stationary player, which is neither what the
     * bind is for nor a shape worth sending.
     */
    private static boolean overAir(FrameState state) {
        Vec3 pos = state.player.pos;
        return Raycast.blockPassable(Maths.floor(pos.x()), Maths.floor(pos.y()) - 1, Maths.floor(pos.z()));
    }

    /* Whether a block placed against this face has somewhere to go. */
    private static boolean placeable(FrameState state, Raycast.BlockHit hit) {
        int x = hit.x() + hit.face().dx;
        int y = hit.y() + hit.face().dy;
        int z = hit.z() + hit.face().dz;
        return Raycast.blockPassable(x, y, z) && !intersectsPlayer(state, x, y, z);
    }

    private static Candidate candidate(Vec3 eye, Vec3 point, float baseYaw, float basePitch) {
        RotationTarget rotation = RotationEngine.aimAt(eye, point, baseYaw);
        double cost = Math.abs(Maths.angleDifference(baseYaw, rotation.yaw)) + Math.abs(rotation.pitch - basePitch);
        return new Candidate(cost, rotation);
    }

    private RotationTarget faceAim(FrameState state, int cellX, int cellY, int cellZ, boolean under) {
        Vec3 eye = state.player.eyePos;
        float baseYaw = RotationEngine.pendingYaw();
        float basePitch = RotationEngine.pendingPitch();
        /* The faces worth clicking are the two the eye is on the near side of,
         * plus the top when the cell sits under the player - a bridge block goes
         * on top of the one below and against the side of everything else. */
        boolean nearSouth = eye.z() > cellZ + 0.5;
        boolean nearEast = eye.x() > cellX + 0.5;
        int samples = (int) Math.round(1.0 / SAMPLE_STEP);
        float jitter = (float) (SAMPLE_STEP * 0.1);

        List<Candidate> candidates = new ArrayList<>((samples + 1) * (samples + 1) * 3 + 1);
        /* The rotation already going out, at no cost at all. Looking at a face
         * that works means clicking it this tick without moving the mouse. */
        candidates.add(new Candidate(0.0, new RotationTarget(baseYaw, basePitch)));

        for (int row = 0; row <= samples; ++row) {
            double v = Maths.clamp(row * SAMPLE_STEP + random.range(-jitter, jitter), 0.0, 1.0);
            for (int column = 0; column <= samples; ++column) {
                double u = Maths.clamp(column * SAMPLE_STEP + random.range(-jitter, jitter), 0.0, 1.0);
                if (under) {
                    candidates.add(candidate(eye, new Vec3(cellX + u, cellY + 1.0 - INSET, cellZ + v), baseYaw,
                            basePitch));
                }
                candidates.add(candidate(eye,
                        new Vec3(cellX + u, cellY + v, nearSouth ? cellZ + 1.0 - INSET : cellZ + INSET), baseYaw,
                        basePitch));
                candidates.add(candidate(eye,
                        new Vec3(nearEast ? cellX + 1.0 - INSET : cellX + INSET, cellY + v, cellZ + u), baseYaw,
                        basePitch));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::cost));

        double distance = reach.number();
        for (Candidate candidate : candidates) {
            if (raysLeft <= 0) {
                return null;
            }
            --raysLeft;
            RotationTarget rotation = candidate.rotation();
            Raycast.BlockHit hit = Raycast.raycastBlocks(eye, Raycast.lookVector(rotation.yaw, rotation.pitch),
                    distance);
            if (hit == null || hit.x() != cellX || hit.y() != cellY || hit.z() != cellZ) {
                continue;
            }
            if (hit.face() == Face.DOWN || (hit.face() == Face.UP && !under)) {
                continue;
            }
            /* The cell the block would land in has to be empty, or the click is
             * spent on nothing. */
            if (placeable(state, hit)) {
                return rotation;
            }
        }
        return null;
    }

    /** Best rotation onto a face within reach to build off, or null. */
    private RotationTarget search(FrameState state) {
        boolean weighted = predict.bool();
        Vec3 pos = state.player.pos;
        Vec3 future = weighted ? futurePosition(state) : pos;

        int feetX = Maths.floor(pos.x());
        int feetY = Maths.floor(pos.y());
        int feetZ = Maths.floor(pos.z());
        double reachSquared = (double) reach.number() * reach.number();

        List<Cell> cells = new ArrayList<>();
        for (int y = feetY - 1; y >= feetY - SEARCH_DEPTH; --y) {
            for (int x = feetX - SEARCH_RADIUS; x <= feetX + SEARCH_RADIUS; ++x) {
                for (int z = feetZ - SEARCH_RADIUS; z <= feetZ + SEARCH_RADIUS; ++z) {
                    /* Nothing out of reach of the eye can be hit by any rotation,
                     * so it is not worth a single ray. */
                    if (cellDistance(state.player.eyePos, x, y, z) > reachSquared || Raycast.blockPassable(x, y, z)) {
                        continue;
                    }
                    double now = cellDistance(pos, x, y, z);
                    double score = weighted ? now * 0.3 + cellDistance(future, x, y, z) * 0.7 : now;
                    if (hasLastPlaced && x == lastPlacedX && y == lastPlacedY && z == lastPlacedZ) {
                        score *= 0.95;
                    }
                    cells.add(new Cell(score, x, y, z));
                }
            }
        }
        cells.sort(Comparator.comparingDouble(Cell::score));

        raysLeft = RAY_BUDGET;
        for (Cell cell : cells) {
            if (raysLeft <= 0) {
                return null;
            }
            RotationTarget found = faceAim(state, cell.x(), cell.y(), cell.z(),
                    underPlayer(state, cell.x(), cell.y(), cell.z()));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int blockSlot() {
        int current = Game.get().selectedSlot();
        if (Game.get().stack(current).block()) {
            return current;
        }
        for (int slot = 8; slot >= 0; --slot) {
            if (Game.get().stack(slot).block()) {
                return slot;
            }
        }
        return -1;
    }

    private void updateAuto(FrameState state) {
        if (!auto.bool()) {
            autoArmed = false;
            autoChecking = false;
            autoLandedGuard = false;
            lastHurtTime = state.player.hurtTime;
            return;
        }

        if (state.player.hurtTime > lastHurtTime) {
            autoChecking = true;
            autoCheckTick = 0;
            autoLandedGuard = false;
        }
        lastHurtTime = state.player.hurtTime;

        /* The prediction is the expensive half - a few dozen block lookups - so
         * it runs every third tick while the check is live rather than every
         * tick. */
        if (autoChecking && !autoArmed && !autoLandedGuard) {
            if (autoCheckTick % 3 == 0 && willFallFar(state, minimumFall.number())) {
                autoArmed = true;
            }
            ++autoCheckTick;
        }

        /* Touching down is not the same as having landed - a ledge on the way
         * past is still a fall. The guard keeps the aim alive for ten ticks and
         * drops it early only on something that says the fall is genuinely
         * over: a jump, or upward motion. */
        if (autoLandedGuard) {
            boolean expired = state.player.age - autoLandedAge >= 10;
            boolean jumped = Game.get().bindingPressed("jump");
            boolean rising = !state.player.onGround && state.player.velocity.y() > 0.0;
            if (expired || jumped || rising) {
                autoArmed = false;
                autoChecking = false;
                autoLandedGuard = false;
            }
        }

        if (autoArmed && !autoLandedGuard && state.player.onGround
                && state.player.hurtTime < state.player.maxHurtTime - 2) {
            autoLandedGuard = true;
            autoLandedAge = state.player.age;
            /* Standing on something that is not about to give way ends it
             * outright. */
            if (!willFallSoon(state)) {
                autoArmed = false;
                autoChecking = false;
                autoLandedGuard = false;
            }
        }

        if (!autoArmed && !autoLandedGuard && state.player.onGround && state.player.hurtTime == 0) {
            autoChecking = false;
            autoCheckTick = 0;
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            onDisable(context);
            return;
        }

        if (state.player.onGround) {
            placed = 0;
        }
        updateAuto(state);

        boolean held = bind.key() != 0 && Input.bindHeld(bind.key());
        if ((!held && !autoArmed) || !overAir(state)) {
            if (aim != null) {
                /* Back to where the player was looking, at the snapback speed,
                 * so the camera does not simply teleport. */
                RotationTarget back = new RotationTarget(state.player.yaw, state.player.pitch);
                back.speed = snapback.number();
                back.smoothing = 0.2f;
                back.silent = silent.bool();
                back.priority = CLUTCH_PRIORITY;
                RotationEngine.submit(back);
                aim = null;
            }
            hasLastPlaced = false;
            giveBindings(state);
            restoreSlot(state);
            return;
        }

        int slot = blockSlot();
        if (slot < 0) {
            aim = null;
            giveBindings(state);
            restoreSlot(state);
            return;
        }

        /* The aim is re-chosen every tick. A fall moves the eye half a block or
         * more between them, so a face that was the nearest one a tick ago
         * rarely still is, and holding onto the old one is how the aim ends up
         * chasing a block it has already fallen past. */
        RotationTarget found = search(state);
        if (found != null) {
            aim = found;
        }
        if (aim == null) {
            giveBindings(state);
            restoreSlot(state);
            return;
        }

        /* An aim is live, so the hand comes off the mouse and the block comes
         * into it - for as long as the aim lasts, not just for the tick a
         * placement happens on. */
        takeBindings();
        if (previousSlot < 0) {
            previousSlot = Game.get().selectedSlot();
        }
        if (plannedSlot != slot) {
            plannedSlot = slot;
            Game.get().setSelectedSlot(slot);
        }

        aim.speed = speed.number();
        aim.smoothing = 0.25f;
        aim.jitter = random.range(0, 1);
        aim.silent = silent.bool();
        aim.priority = CLUTCH_PRIORITY;
        RotationEngine.submit(aim);

        int limit = (int) maxBlocks.number();
        if (limit > 0 && placed >= limit) {
            return;
        }

        /* The engine stepped the aim at the top of this tick, so the rotation in
         * this tick's movement packet is one step behind the aim just chosen.
         * That rotation, and nothing else, decides the placement: cast it, take
         * the face it lands on, and click there. Demanding it land on the exact
         * cell the search picked would mean never clicking at all - the search
         * moves cell every tick while the aim is still travelling towards the
         * last one. */
        float packetYaw = RotationEngine.pendingYaw();
        float packetPitch = RotationEngine.pendingPitch();
        if (Math.abs(Maths.angleDifference(packetYaw, aim.yaw)) > tolerance.number()
                || Math.abs(packetPitch - aim.pitch) > tolerance.number()) {
            return;
        }

        Raycast.BlockHit hit = Raycast.raycastBlocks(state.player.eyePos, Raycast.lookVector(packetYaw, packetPitch),
                reach.number());
        boolean usable = hit != null && hit.y() < Maths.floor(state.player.pos.y()) && hit.face() != Face.DOWN
                && (hit.face() != Face.UP || underPlayer(state, hit.x(), hit.y(), hit.z()))
                && placeable(state, hit);
        if (!usable) {
            return;
        }

        hasLastPlaced = true;
        lastPlacedX = hit.x();
        lastPlacedY = hit.y();
        lastPlacedZ = hit.z();

        /* A block set on top of the one under the feet is the first tick of every
         * clutch and costs nothing, so it does not come out of the budget - only
         * the ones built out to the side do. */
        boolean counts = hit.face() != Face.UP;

        if (clickMode.choice() == 0) {
            /* One press onto the use binding. The game drains it later in this
             * same tick and runs the whole of vanilla rightClickMouse from there -
             * the packet, the swing, the cooldown - so the placement leaves from
             * where Minecraft leaves from.
             *
             * rightClickMouse works off the crosshair, which the game picked from
             * the camera at the top of this tick. That is the wrong face whenever
             * the aim has moved since, and it is always the wrong face with a
             * silent rotation, which never touches the camera at all. So the
             * confirmed hit is written into the crosshair first: same face, same
             * hit vector, same ray the rotation about to go out produces. */
            Game.get().setCrosshairTarget(hit.x(), hit.y(), hit.z(), hit.face(), hit.point());
            /* The vanilla use path parks a four tick cooldown of its own. It only
             * gates the held button, which is already down for the player here,
             * so clearing it costs nothing and is the difference between a block
             * a tick and a block every fifth one. */
            Game.get().setItemUseCooldown(0);
            Game.get().queueClick(true);
            if (counts) {
                ++placed;
            }
            return;
        }
        if (Game.get().placeBlock(hit.x(), hit.y(), hit.z(), hit.face(), hit.point(), true) && counts) {
            ++placed;
        }
    }
}
