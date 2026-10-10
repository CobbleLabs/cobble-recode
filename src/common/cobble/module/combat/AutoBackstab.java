package cobble.module.combat;

import cobble.boot.GameVersion;
import cobble.core.Log;
import cobble.core.Maths;
import cobble.core.Time;
import cobble.event.AttackEntityEvent;
import cobble.event.Subscribe;
import cobble.game.CrosshairTarget;
import cobble.game.EntityView;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HCF rogues backstab with a golden sword, and the server counts it when the
 * attacker faces the same way as the victim: HCTeams compares the two look
 * directions, not where either stands.
 *
 * <p>Front lands your own golden sword hits from the front. Each is held back
 * and replayed so the server sees exactly one movement packet facing the
 * victim's way, then the hit, then the camera's rotation again:
 *
 * <ul>
 * <li>The backstab rotation goes out silently in one packet, a tick the
 * movement keys are let go: Grim simulates that tick with the rotation it
 * was sent, and with no input a tick moves the same whichever way you face.
 * <li>The hit follows with its swing ahead of it, which is the order 1.8
 * sends them in and the one Grim's PacketOrderB holds a 1.8 client to.
 * <li>Grim checks the hit against the rotation of the packet after it, so
 * that one is the camera's, still on the victim.
 * </ul>
 *
 * <p>Back turns nothing. The moment the player under your crosshair shows
 * you their back it takes the golden sword from the hotbar, hits, and goes
 * back to what you held the tick after. The slot change goes between the
 * swing and the hit, where 1.8 itself puts one.
 *
 * <p>Their facing is read from the head, which takes the server's value the
 * tick it arrives, not from yaw, which the client eases toward it over three
 * ticks. It is carried forward along their current turn to when the hit
 * lands: what we see left the server half a ping ago, and the hit takes the
 * other half to get there. While the server's cooldown runs nothing happens,
 * since a hit then is an ordinary one.
 *
 * <p>For half its hurt cooldown after a hit lands, a 1.8 server drops any hit
 * weaker than that one without firing the damage event a backstab hangs on,
 * and a golden sword hits weaker than a diamond one. So a backstab waits until
 * the victim can be hurt again, and in Back your own hits on a turned back are
 * called off rather than spent on that window.
 */
public final class AutoBackstab extends Module {
    private static final int FRONT = 0;
    private static final int AIM_PRIORITY = 75;
    /* Long enough to wait out a hurt cooldown and still turn and hit. */
    private static final int GIVE_UP_TICKS = 15;
    /* Front aims at their exact facing; this much is what their head may turn between our
     * rotation going out and the hit. HCTeams accepts up to 80 degrees, stricter cores less. */
    private static final float TOLERANCE = 30.0f;
    /* How far from their facing Back still takes a back to be turned, with 20 degrees left under HCTeams' 80. */
    private static final float BACK_WINDOW = 60.0f;
    /* Half the 20 tick hurt cooldown, after which a weaker hit lands again. */
    private static final int VULNERABLE_AT = 10;
    private static final long INVULNERABLE_MS = VULNERABLE_AT * 50L;
    /* A rotation asked for in one tick goes out in the next tick's packet; the hit follows a tick after. */
    private static final float AIM_TO_HIT_TICKS = 2.0f;
    private static final long AIM_TO_HIT_MS = (long) (AIM_TO_HIT_TICKS * 50.0f);
    private static final float MAX_LEAD_TICKS = 6.0f;
    /* A head that moves further than this in a tick was teleported or respawned, not turned. */
    private static final float MAX_TURN = 60.0f;
    private static final List<String> MOVEMENT = List.of("forward", "back", "left", "right");

    private enum Phase { IDLE, WAITING, TURNING, STRIKE }

    /** Head yaw last tick and degrees turned per tick, averaged so the 5.6 degree steps the server sends even out. */
    private record Turn(float headYaw, float rate) {}

    private final Setting mode = addChoice("Mode",
            "Front lands your golden sword hits from the front; Back swaps to the sword and hits when they turn their back",
            List.of("Front", "Back"), FRONT);
    private final Setting cooldown = addNumber("Cooldown",
            "Seconds the server makes you wait between backstabs; HCTeams uses 1.5", 1.5f, 0, 10, 1);

    private FrameState state;
    private Phase phase = Phase.IDLE;
    private Object victim;
    private int ticks;
    private boolean striking;
    private boolean movementReleased;
    private int swappedFrom = -1;
    private long readyAt;
    private final Map<Object, Turn> turns = new HashMap<>();
    /* When each player can next be hurt by a weaker hit, from the hits we landed on them. */
    private final Map<Object, Long> vulnerableAt = new HashMap<>();

    public AutoBackstab() {
        super("AutoBackstab", "Lands golden sword hits as backstabs", Category.COMBAT, Subcategory.MEDIUM);
    }

    @Override
    public boolean supported() {
        return !GameVersion.current().combatUpdate();
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    protected void settingChanged(Setting setting) {
        if (setting == mode) {
            cancelFront();
        }
    }

    @Override
    public void onDisable(Context context) {
        cancelFront();
        if (swappedFrom >= 0 && context.state.player.valid) {
            Game.get().setSelectedSlot(swappedFrom);
        }
        swappedFrom = -1;
        turns.clear();
        vulnerableAt.clear();
    }

    @Subscribe(priority = Subscribe.Priority.HIGHEST)
    public void onAttack(AttackEntityEvent event) {
        Game game = Game.get();
        if (striking || state == null || !ready() || !game.isPlayer(event.entity)) {
            return;
        }
        EntityView target = find(event.entity);
        if (target == null) {
            return;
        }
        if (mode.choice() == FRONT) {
            if (!game.holdingItem(ItemKind.GOLDEN_SWORD)) {
                return;
            }
            event.setCancelled(true);
            if (phase == Phase.IDLE) {
                victim = event.entity;
                ticks = 0;
                approach(game, target);
            }
        } else if (game.findSlot(ItemKind.GOLDEN_SWORD, 0, 8) >= 0 && facesWithin(target, BACK_WINDOW)) {
            event.setCancelled(true);
        }
    }

    /* Last, so a hit another module called off does not count as landed. */
    @Subscribe(priority = Subscribe.Priority.LOWEST)
    public void onLanded(AttackEntityEvent event) {
        long now = Time.nowMillis();
        if (!event.cancelled() && now >= vulnerableAt.getOrDefault(event.entity, 0L)) {
            vulnerableAt.put(event.entity, now + INVULNERABLE_MS);
        }
    }

    @Override
    public void onTick(Context context) {
        state = context.state;
        track();
        Game game = Game.get();
        if (swappedFrom >= 0) {
            game.setSelectedSlot(swappedFrom);
            swappedFrom = -1;
        }
        if (mode.choice() == FRONT) {
            front(game);
        } else {
            back(game);
        }
    }

    private void front(Game game) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (phase == Phase.TURNING) {
            /* This tick's packet carries the backstab rotation whatever happens now. */
            holdStill(game);
            phase = Phase.STRIKE;
            return;
        }
        restoreMovement(game);
        EntityView target = find(victim);
        if (!state.player.valid || state.screenOpen || !game.holdingItem(ItemKind.GOLDEN_SWORD) || target == null
                || ++ticks > GIVE_UP_TICKS) {
            cancelFront();
            return;
        }
        if (phase == Phase.STRIKE && facesWithin(target, TOLERANCE) && crosshairOn(game, target)
                && vulnerable(game, target, 0)) {
            striking = true;
            game.swingHand();
            game.attackEntity(target.handle);
            striking = false;
            startCooldown();
            Log.debug("backstab", "front hit on entity %d", target.id);
            cancelFront();
        } else {
            approach(game, target);
        }
    }

    /* The turn goes out only once the hit it sets up will land. */
    private void approach(Game game, EntityView target) {
        if (vulnerable(game, target, AIM_TO_HIT_MS)) {
            aim(target);
        } else {
            phase = Phase.WAITING;
        }
    }

    private void back(Game game) {
        if (!state.player.valid || state.screenOpen || !ready()) {
            return;
        }
        EntityView target = crosshairPlayer(game);
        int sword = game.findSlot(ItemKind.GOLDEN_SWORD, 0, 8);
        if (target == null || sword < 0 || !vulnerable(game, target, 0) || !facesWithin(target, BACK_WINDOW)) {
            return;
        }
        int held = game.selectedSlot();
        striking = true;
        game.swingHand();
        if (held != sword) {
            game.setSelectedSlot(sword);
            swappedFrom = held;
        }
        game.attackEntity(target.handle);
        striking = false;
        startCooldown();
        Log.debug("backstab", "back hit on entity %d", target.id);
    }

    private void cancelFront() {
        if (movementReleased) {
            restoreMovement(Game.get());
        }
        phase = Phase.IDLE;
        victim = null;
    }

    private void startCooldown() {
        readyAt = Time.nowMillis() + (long) (cooldown.number() * 1000.0f);
    }

    private void holdStill(Game game) {
        for (String key : MOVEMENT) {
            game.setBinding(key, false);
        }
        movementReleased = true;
    }

    private void restoreMovement(Game game) {
        if (!movementReleased) {
            return;
        }
        for (String key : MOVEMENT) {
            game.setBinding(key, game.bindingPhysicallyHeld(key));
        }
        movementReleased = false;
    }

    private boolean ready() {
        return Time.nowMillis() >= readyAt;
    }

    private EntityView crosshairPlayer(Game game) {
        CrosshairTarget crosshair = game.crosshair();
        return crosshair.kind() == CrosshairTarget.Kind.ENTITY && game.isPlayer(crosshair.entity())
                ? find(crosshair.entity()) : null;
    }

    /* The packet after the hit carries the camera's rotation, so the camera is what has to be on them. */
    private boolean crosshairOn(Game game, EntityView target) {
        CrosshairTarget crosshair = game.crosshair();
        return crosshair.kind() == CrosshairTarget.Kind.ENTITY && crosshair.entity() == target.handle;
    }

    private EntityView find(Object handle) {
        for (EntityView entity : state.entities) {
            if (entity.handle == handle) {
                return entity;
            }
        }
        return null;
    }

    private void track() {
        Set<Object> present = new HashSet<>();
        for (EntityView entity : state.entities) {
            if (!entity.isPlayer) {
                continue;
            }
            present.add(entity.handle);
            Turn last = turns.get(entity.handle);
            float rate = 0.0f;
            if (last != null) {
                float step = Maths.angleDifference(last.headYaw, entity.headYaw);
                rate = Math.abs(step) > MAX_TURN ? 0.0f : (last.rate + step) / 2.0f;
            }
            turns.put(entity.handle, new Turn(entity.headYaw, rate));
        }
        turns.keySet().retainAll(present);
        vulnerableAt.keySet().retainAll(present);
    }

    /**
     * Whether a hit sent this far ahead lands. Our own hits are timed here;
     * anyone else's only show in the client's hurt cooldown, a ping late.
     */
    private boolean vulnerable(Game game, EntityView target, long inMs) {
        return Time.nowMillis() + inMs >= vulnerableAt.getOrDefault(target.handle, 0L)
                && game.hurtResistance(target.handle) <= VULNERABLE_AT + inMs / 50;
    }

    /** Where they will face when a hit sent this many ticks from now reaches the server. */
    private float facing(EntityView target, float ticksUntilHit) {
        Turn turn = turns.get(target.handle);
        float rate = turn == null ? 0.0f : turn.rate;
        return target.headYaw + rate * Maths.clamp(ticksUntilHit + state.ping / 50.0f, 0.0f, MAX_LEAD_TICKS);
    }

    /* What a hit sent now is judged by: the last rotation the server was sent. */
    private boolean facesWithin(EntityView target, float degrees) {
        return Math.abs(Maths.angleDifference(RotationEngine.sentYaw(), facing(target, 0.0f))) <= degrees;
    }

    private void aim(EntityView target) {
        RotationTarget rotation = new RotationTarget(facing(target, AIM_TO_HIT_TICKS), state.player.pitch);
        rotation.speed = 180.0f;
        rotation.smoothing = 0.0f;
        rotation.silent = true;
        rotation.priority = AIM_PRIORITY;
        RotationEngine.submit(rotation);
        phase = Phase.TURNING;
    }
}
