package cobble.v1_21;

import cobble.core.Vec3;
import cobble.event.AttackEntityEvent;
import cobble.event.EventBus;
import cobble.event.UseSlotEvent;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.RotationEngine;
import cobble.module.ModuleManager;
import cobble.module.combat.HitBoxes;
import cobble.module.movement.NoSlowDown;
import cobble.module.render.NoCameraClip;
import cobble.ui.Client;
import cobble.v1_21.compat.TimerShim;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UseEffectsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** The handlers the patched 1.21.11 methods call; see {@link HookTable} for where each one sits. */
public final class Hooks {
    /* The last world pass's camera, which the frame projects world-space overlays with. */
    private static final float[] view = new float[16];
    private static final float[] projection = new float[16];
    private static Vec3 cameraPos = Vec3.ZERO;
    private static float cameraYaw;
    private static float cameraPitch;
    private static boolean matricesSeen;

    private Hooks() {}

    public static void tick() {
        Client.tick();
    }

    public static void tickEnd() {
        if (Game.get().inWorld()) {
            ModuleManager.update();
        }
    }

    public static void frame() {
        Client.frame();
    }

    public static boolean attackEntity(PlayerEntity player, Entity target) {
        return EventBus.post(new AttackEntityEvent(target, player, true)).cancelled();
    }

    /* The use packet goes out whatever the cooldown; only a stack off it does anything. */
    public static void useItem(PlayerEntity player, Hand hand) {
        if (!player.getItemCooldownManager().isCoolingDown(player.getStackInHand(hand))) {
            EventBus.post(new UseSlotEvent(hand == Hand.MAIN_HAND ? player.getInventory().getSelectedSlot()
                    : PlayerInventory.OFF_HAND_SLOT));
        }
    }

    public static float targetingMargin(float margin, Entity entity) {
        return entity instanceof LivingEntity && entity != MinecraftClient.getInstance().player
                ? margin + HitBoxes.expansion() : margin;
    }

    /* The third person camera is pulled in to the first block behind it; NoCameraClip keeps the full distance. */
    public static float cameraClip(float clipped, float desired) {
        return ModuleManager.get(NoCameraClip.class).enabled() ? desired : clipped;
    }

    /* The game's tick length, stretched or shrunk by the timer speed the legacy modules set. */
    public static float tickLength(FloatUnaryOperator length, float tickTime) {
        return length.apply(tickTime) / TimerShim.speed();
    }

    public static void preMotion(ClientPlayerEntity player) {
        float[] silent = RotationEngine.preMotion(player.getYaw(), player.getPitch());
        if (silent != null) {
            player.setYaw(silent[0]);
            player.setPitch(silent[1]);
        }
    }

    /*
     * The input has been scaled by the item's use speed and then capped at a
     * length of 1, so the use speed is swapped by rescaling the length.
     */
    public static Vec2f movementSpeed(Vec2f movement, ClientPlayerEntity player) {
        NoSlowDown noSlow = ModuleManager.get(NoSlowDown.class);
        NoSlowDown.Use use = use(player);
        if (!player.isUsingItem() || player.hasVehicle() || !noSlow.covers(use) || movement.lengthSquared() == 0.0f) {
            return movement;
        }
        float slowed = useEffects(player).speedMultiplier();
        float length = movement.length();
        float scaled = length * Math.max(slowed, noSlow.speed(use)) / slowed;
        return movement.multiply(Math.min(scaled, 1.0f) / length);
    }

    public static boolean sprintBlocked(boolean blocked, ClientPlayerEntity player) {
        return blocked && !ModuleManager.get(NoSlowDown.class).covers(use(player));
    }

    private static UseEffectsComponent useEffects(ClientPlayerEntity player) {
        return player.getActiveItem().getOrDefault(DataComponentTypes.USE_EFFECTS, UseEffectsComponent.DEFAULT);
    }

    private static NoSlowDown.Use use(ClientPlayerEntity player) {
        return switch (player.getActiveItem().getUseAction()) {
            case EAT, DRINK -> NoSlowDown.Use.CONSUME;
            case BLOCK -> NoSlowDown.Use.BLOCK;
            case BOW, CROSSBOW, TRIDENT -> NoSlowDown.Use.BOW;
            default -> NoSlowDown.Use.OTHER;
        };
    }

    public static void postMotion(ClientPlayerEntity player) {
        float[] restored = RotationEngine.postMotion(player.lastYawClient, player.lastPitchClient);
        if (restored != null) {
            player.setYaw(restored[0]);
            player.setPitch(restored[1]);
        }
    }

    /*
     * The world is drawn relative to the camera: positionMatrix is its
     * rotation alone and the projection carries the view bob and the nausea
     * warp, so the pair projects a point taken relative to the camera.
     */
    public static void worldRendered(ObjectAllocator allocator, RenderTickCounter counter, boolean outline,
                                     Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        positionMatrix.get(view);
        projectionMatrix.get(projection);
        Vec3d pos = camera.getCameraPos();
        cameraPos = new Vec3(pos.x, pos.y, pos.z);
        cameraYaw = camera.getYaw();
        cameraPitch = camera.getPitch();
        matricesSeen = true;
    }

    static boolean captureMatrices(FrameState state) {
        System.arraycopy(view, 0, state.view.m, 0, 16);
        System.arraycopy(projection, 0, state.projection.m, 0, 16);
        state.camera.pos = cameraPos;
        state.camera.yaw = cameraYaw;
        state.camera.pitch = cameraPitch;
        return matricesSeen && projection[0] != 0.0f && projection[5] != 0.0f;
    }
}
