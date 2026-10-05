package cobble.module.combat;

import cobble.boot.GameVersion;
import cobble.core.Rng;
import cobble.core.Timer;
import cobble.core.Vec3;
import cobble.game.BlockKind;
import cobble.game.CrosshairTarget;
import cobble.game.EntityView;
import cobble.game.Face;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.combat.crystalaura.CrystalDamage;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual: holding use with a crystal over obsidian or
 * bedrock places one, and one under the crosshair is broken, everything where
 * the player looks. Auto: picks the crystal to break and the block to place
 * on that hurt the nearest players most while keeping what comes back at you
 * under a limit, then turns the rotation the server sees to it and acts once
 * the ray from that rotation lands on it.
 */
public final class CrystalAura extends Module {
    private static final int MANUAL = 0;
    private static final int AIM_PRIORITY = 70;
    /* A crystal's box: two blocks wide and tall, from its feet. */
    private static final double CRYSTAL_HALF = 1.0;
    private static final double CRYSTAL_HEIGHT = 2.0;

    private final Setting mode = addChoice("Mode",
            "Manual places and breaks where you look; Auto finds where to on its own", List.of("Manual", "Auto"),
            MANUAL);
    private final Setting placeMin = addNumber("Place delay min", "Shortest wait between placements, in ms", 50, 0,
            500, 0);
    private final Setting placeMax = addNumber("Place delay max", "Longest wait between placements, in ms", 100, 0,
            500, 0);
    private final Setting breakMin = addNumber("Break delay min", "Shortest wait between breaks, in ms", 50, 0, 500,
            0);
    private final Setting breakMax = addNumber("Break delay max", "Longest wait between breaks, in ms", 100, 0, 500,
            0);
    private final Setting requireUse = addBoolean("Require use", "Only while holding the use button", true)
            .visibleWhen(this::manual);
    private final Setting targetRange = addNumber("Target range", "Players this close are targets", 10, 4, 16, 0)
            .visibleWhen(this::auto);
    private final Setting placeRange = addNumber("Place range", "Farthest block to place a crystal on", 4.5f, 2, 6, 1)
            .visibleWhen(this::auto);
    private final Setting breakRange = addNumber("Break range", "Farthest crystal to break", 4.5f, 2, 6, 1)
            .visibleWhen(this::auto);
    private final Setting minDamage = addNumber("Min damage", "Least damage to a target worth a crystal", 6, 0, 20, 1)
            .visibleWhen(this::auto);
    private final Setting maxSelfDamage = addNumber("Max self damage", "Most damage a crystal may do to you", 8, 0,
            20, 1).visibleWhen(this::auto);
    private final Setting antiSuicide = addBoolean("Anti suicide", "Never set off a crystal that would kill you", true)
            .visibleWhen(this::auto);
    private final Setting autoSwitch = addBoolean("Auto switch", "Take a crystal from the hotbar to place", true)
            .visibleWhen(this::auto);
    private final Setting aimSpeed = addNumber("Aim speed", "Degrees the rotation turns each tick", 60, 5, 180, 0)
            .visibleWhen(this::auto);

    private final Rng rng = new Rng();
    private final Timer placeTimer = new Timer();
    private final Timer breakTimer = new Timer();
    private long placeDelay;
    private long breakDelay;

    private record Placement(int x, int y, int z, float damage) {}

    private record Break(Game.EntityRef crystal, float damage) {}

    public CrystalAura() {
        super("CrystalAura", "Places and breaks end crystals", Category.COMBAT, Subcategory.ADVANCED);
    }

    private boolean manual() {
        return mode.choice() == MANUAL;
    }

    private boolean auto() {
        return !manual();
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
        placeDelay = roll(placeMin, placeMax);
        breakDelay = roll(breakMin, breakMax);
    }

    private long roll(Setting min, Setting max) {
        int low = (int) Math.min(min.number(), max.number());
        int high = (int) Math.max(min.number(), max.number());
        return high <= low ? low : rng.range(low, high);
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || state.screenOpen) {
            return;
        }
        if (manual()) {
            manualTick(Game.get());
        } else {
            autoTick(state, Game.get());
        }
    }

    private void manualTick(Game game) {
        if (!game.holdingItem(ItemKind.END_CRYSTAL) || requireUse.bool() && !Input.useHeld()) {
            return;
        }
        CrosshairTarget target = game.crosshair();
        if (target.kind() == CrosshairTarget.Kind.ENTITY && target.crystal()) {
            if (breakTimer.elapsed(breakDelay)) {
                game.attackEntity(target.entity());
                game.swingHand();
                breakTimer.reset();
                breakDelay = roll(breakMin, breakMax);
            }
        } else if (target.kind() == CrosshairTarget.Kind.BLOCK && placeable(game, target.x(), target.y(), target.z())) {
            if (placeTimer.elapsed(placeDelay)) {
                game.placeBlock(target.x(), target.y(), target.z(), target.face(), target.point(), true);
                placeTimer.reset();
                placeDelay = roll(placeMin, placeMax);
            }
        }
    }

    /* A crystal stands on obsidian or bedrock with air above it. */
    private static boolean placeable(Game game, int x, int y, int z) {
        return (game.blockIs(x, y, z, BlockKind.OBSIDIAN) || game.blockIs(x, y, z, BlockKind.BEDROCK))
                && game.isBlockAir(x, y + 1, z);
    }

    /* One step a tick: breaking a crystal that is there comes before placing the next. */
    private void autoTick(FrameState state, Game game) {
        List<CrystalDamage.Victim> targets = targets(state);
        if (targets.isEmpty()) {
            return;
        }
        CrystalDamage.Victim self = new CrystalDamage.Victim(state.player.handle,
                new Vec3(state.player.pos.x() - 0.3, state.player.pos.y(), state.player.pos.z() - 0.3),
                new Vec3(state.player.pos.x() + 0.3, state.player.pos.y() + 1.8, state.player.pos.z() + 0.3),
                state.player.armor);
        float health = state.player.health;
        Break hit = bestBreak(state, game, targets, self, health);
        if (hit != null) {
            Vec3 pos = hit.crystal.pos();
            Vec3 min = new Vec3(pos.x() - CRYSTAL_HALF, pos.y(), pos.z() - CRYSTAL_HALF);
            Vec3 max = new Vec3(pos.x() + CRYSTAL_HALF, pos.y() + CRYSTAL_HEIGHT, pos.z() + CRYSTAL_HALF);
            aim(state, pos.add(0.0, CRYSTAL_HEIGHT * 0.5, 0.0));
            if (breakTimer.elapsed(breakDelay) && Raycast.rayBox(state.player.eyePos, pendingLook(), min, max,
                    breakRange.number()) >= 0.0) {
                game.attackEntity(hit.crystal.handle());
                game.swingHand();
                breakTimer.reset();
                breakDelay = roll(breakMin, breakMax);
            }
            return;
        }
        int slot = game.findSlot(ItemKind.END_CRYSTAL, 0, 8);
        if (slot < 0 || !autoSwitch.bool() && !game.holdingItem(ItemKind.END_CRYSTAL)) {
            return;
        }
        Placement place = bestPlacement(state, game, targets, self, health);
        if (place == null) {
            return;
        }
        Vec3 top = new Vec3(place.x + 0.5, place.y + 1.0, place.z + 0.5);
        aim(state, top);
        if (!placeTimer.elapsed(placeDelay)) {
            return;
        }
        if (!game.holdingItem(ItemKind.END_CRYSTAL)) {
            game.setSelectedSlot(slot);
            return;
        }
        Raycast.BlockHit ray = Raycast.raycastBlocks(state.player.eyePos, pendingLook(), placeRange.number());
        if (ray == null || ray.x() != place.x || ray.y() != place.y || ray.z() != place.z || ray.face() != Face.UP) {
            return;
        }
        /* The game's own use, from a crosshair set to the ray just cast; see AutoAnchor. */
        game.setCrosshairTarget(ray.x(), ray.y(), ray.z(), ray.face(), ray.point());
        game.setItemUseCooldown(0);
        game.queueClick(true);
        placeTimer.reset();
        placeDelay = roll(placeMin, placeMax);
    }

    private void aim(FrameState state, Vec3 point) {
        RotationTarget aim = RotationEngine.aimAt(state.player.eyePos, point, RotationEngine.pendingYaw());
        aim.speed = aimSpeed.number();
        aim.silent = true;
        aim.priority = AIM_PRIORITY;
        RotationEngine.submit(aim);
    }

    private static Vec3 pendingLook() {
        return Raycast.lookVector(RotationEngine.pendingYaw(), RotationEngine.pendingPitch());
    }

    private List<CrystalDamage.Victim> targets(FrameState state) {
        List<CrystalDamage.Victim> targets = new ArrayList<>();
        for (EntityView entity : state.entities) {
            if (entity.isPlayer && entity.health > 0.0f
                    && entity.pos.sub(state.player.pos).length() <= targetRange.number()) {
                targets.add(new CrystalDamage.Victim(entity.handle, entity.tickBoxMin(), entity.tickBoxMax(),
                        entity.armor));
            }
        }
        return targets;
    }

    /* Most damage to any one target from a blast here, or -1 when it costs us too much. */
    private float worth(Game game, List<CrystalDamage.Victim> targets, CrystalDamage.Victim self, float health,
                        Vec3 blast) {
        float selfDamage = CrystalDamage.damage(game, self, blast);
        if (selfDamage > maxSelfDamage.number() || antiSuicide.bool() && selfDamage >= health) {
            return -1.0f;
        }
        float best = -1.0f;
        for (CrystalDamage.Victim target : targets) {
            best = Math.max(best, CrystalDamage.damage(game, target, blast));
        }
        return best >= minDamage.number() ? best : -1.0f;
    }

    private Break bestBreak(FrameState state, Game game, List<CrystalDamage.Victim> targets,
                            CrystalDamage.Victim self, float health) {
        Break best = null;
        for (Game.EntityRef crystal : game.endCrystals()) {
            Vec3 pos = crystal.pos();
            if (pos.add(0.0, CRYSTAL_HEIGHT * 0.5, 0.0).sub(state.player.eyePos).length() > breakRange.number()) {
                continue;
            }
            float damage = worth(game, targets, self, health, pos);
            if (damage >= 0.0f && (best == null || damage > best.damage)) {
                best = new Break(crystal, damage);
            }
        }
        return best;
    }

    private Placement bestPlacement(FrameState state, Game game, List<CrystalDamage.Victim> targets,
                                    CrystalDamage.Victim self, float health) {
        Vec3 eye = state.player.eyePos;
        int reach = (int) Math.ceil(placeRange.number());
        int baseX = (int) Math.floor(eye.x());
        int baseY = (int) Math.floor(eye.y());
        int baseZ = (int) Math.floor(eye.z());
        Placement best = null;
        for (int x = baseX - reach; x <= baseX + reach; ++x) {
            for (int y = baseY - reach; y <= baseY + reach; ++y) {
                for (int z = baseZ - reach; z <= baseZ + reach; ++z) {
                    Vec3 top = new Vec3(x + 0.5, y + 1.0, z + 0.5);
                    if (top.sub(eye).length() > placeRange.number() || !placeable(game, x, y, z)
                            || !game.boxEmpty(new Vec3(x, y + 1, z), new Vec3(x + 1, y + 3, z + 1))) {
                        continue;
                    }
                    float damage = worth(game, targets, self, health, top);
                    if (damage >= 0.0f && (best == null || damage > best.damage)) {
                        best = new Placement(x, y, z, damage);
                    }
                }
            }
        }
        return best;
    }
}
