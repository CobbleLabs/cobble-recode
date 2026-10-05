package cobble.module.player;

import cobble.core.Maths;
import cobble.core.Time;
import cobble.event.MoveInputEvent;
import cobble.event.Subscribe;
import cobble.game.Game;
import cobble.game.RotationEngine;
import cobble.game.RotationTarget;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.SplittableRandom;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiInventory;

/**
 * Keeps you from being kicked for idling. Once you have not touched the
 * movement keys for the start delay, it shuffles a step in some direction and
 * glances around now and then; any key you press hands control straight back.
 */
public final class AntiAFK extends LegacyModule {
    private static final String[] MOVEMENT = {"forward", "back", "left", "right", "jump", "sneak"};
    /* Close enough to the glance's end to stop steering. */
    private static final float SETTLED = 0.5f;

    private final Setting startMin = addNumber("Start delay min", "Least seconds idle before it starts", 30, 10, 200, 0);
    private final Setting startMax = addNumber("Start delay max", "Most seconds idle before it starts", 40, 10, 200, 0);
    private final Setting frequency = addNumber("Frequency", "How often you move", 0.2f, 0.1f, 20.0f, 1);
    private final Setting keepClose = addBoolean("Keep close",
            "Keeps your position and rotation close to the original", false);
    private final Setting rotation = addBoolean("Rotation", "Moves your camera around", true);
    private final Setting silentAim = addBoolean("Silent aim", "Turn only for the server", false)
            .visibleWhen(rotation::bool);
    private final Setting maxYaw = addNumber("Max yaw change", "Most degrees it turns left or right", 10, 1, 180, 0)
            .visibleWhen(rotation::bool);
    private final Setting maxPitch = addNumber("Max pitch change", "Most degrees it tilts up or down", 5, 1, 90, 0)
            .visibleWhen(rotation::bool);

    private final SplittableRandom random = new SplittableRandom();
    private long idleSince;
    private long startDelay;
    private long nextGlanceAt;
    private long nextStepAt;
    private long stepUntil = -1;
    private double stepX;
    private double stepZ;
    private boolean glancing;
    private float glanceYaw;
    private float glancePitch;
    private float glanceSpeed;
    private boolean anchored;
    private double homeX;
    private double homeZ;
    private float homeYaw;
    private float homePitch;

    public AntiAFK() {
        super("AntiAFK", "Keeps you from being kicked for idling", Category.PLAYER, Subcategory.BASIC);
    }

    @Override
    public void onEnable(Context context) {
        reset();
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.currentScreen instanceof GuiInventory) {
            return;
        }
        if (touched()) {
            reset();
            return;
        }
        long now = Time.nowMillis();
        if (now - idleSince < startDelay) {
            return;
        }
        if (!anchored) {
            anchored = true;
            homeX = player.posX;
            homeZ = player.posZ;
            homeYaw = player.rotationYaw;
            homePitch = player.rotationPitch;
        }
        int interval = (int) (1000.0f / frequency.number());
        if (rotation.bool() && mc.currentScreen == null && now >= nextGlanceAt) {
            nextGlanceAt = now + random.nextInt(1000 + interval);
            pickGlance(player);
        }
        if (glancing) {
            steer(player);
        }
        if (stepUntil < 0 && now >= nextStepAt) {
            nextStepAt = now + random.nextInt(1000 + interval);
            stepUntil = now + 100 + random.nextInt(100);
            pickStep(player);
        } else if (stepUntil >= 0 && now >= stepUntil) {
            stepUntil = -1;
        }
    }

    /* While a step is on, the keys read as walking toward its point, whichever way you face. */
    @Subscribe
    public void onMoveInput(MoveInputEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (stepUntil < 0 || player == null) {
            return;
        }
        double yaw = Math.toRadians(player.rotationYaw);
        double dx = stepX - player.posX;
        double dz = stepZ - player.posZ;
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double strafe = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        double length = Math.hypot(forward, strafe);
        if (length < 0.05) {
            stepUntil = -1;
            return;
        }
        event.setMoveForward((float) (forward / length));
        event.setMoveStrafe((float) (strafe / length));
    }

    private void pickStep(EntityPlayerSP player) {
        stepX = player.posX + random.nextDouble() * 2.0 - 1.0;
        stepZ = player.posZ + random.nextDouble() * 2.0 - 1.0;
        if (keepClose.bool() && Math.hypot(homeX - player.posX, homeZ - player.posZ) >= 0.75) {
            stepX = homeX;
            stepZ = homeZ;
        }
    }

    /* A little either way, or straight back home once Keep close finds it has wandered too far. */
    private void pickGlance(EntityPlayerSP player) {
        float yaw = player.rotationYaw;
        float pitch = player.rotationPitch;
        double reach = Math.hypot(maxYaw.number(), maxPitch.number());
        double away = Math.hypot(Maths.wrapDegrees(yaw - homeYaw), Maths.wrapDegrees(pitch - homePitch));
        if (keepClose.bool() && away >= reach) {
            glanceYaw = homeYaw;
            glancePitch = homePitch;
        } else {
            double yawChange = random.nextDouble() * maxYaw.number() * (random.nextDouble() > 0.6 ? -1 : 1);
            double pitchChange = random.nextDouble() * maxPitch.number() * (random.nextDouble() > 0.5 ? -1 : 1);
            glanceYaw = (float) (yaw + yawChange);
            glancePitch = Maths.clamp((float) (pitch + pitchChange), -90.0f, 90.0f);
        }
        glanceSpeed = 1.0f + (float) random.nextDouble() * 9.0f;
        glancing = true;
    }

    private void steer(EntityPlayerSP player) {
        float yaw = silentAim.bool() ? RotationEngine.yaw() : player.rotationYaw;
        float pitch = silentAim.bool() ? RotationEngine.pitch() : player.rotationPitch;
        if (Math.abs(Maths.wrapDegrees(glanceYaw - yaw)) < SETTLED && Math.abs(glancePitch - pitch) < SETTLED) {
            glancing = false;
            return;
        }
        RotationTarget target = new RotationTarget(glanceYaw, glancePitch);
        target.speed = glanceSpeed;
        target.silent = silentAim.bool();
        RotationEngine.submit(target);
    }

    private boolean touched() {
        for (String binding : MOVEMENT) {
            if (Game.get().bindingPhysicallyHeld(binding)) {
                return true;
            }
        }
        return false;
    }

    private void reset() {
        idleSince = Time.nowMillis();
        int low = Math.min(startMin.intValue(), startMax.intValue());
        int high = Math.max(startMin.intValue(), startMax.intValue());
        startDelay = (low + random.nextInt(high - low + 1)) * 1000L;
        nextGlanceAt = idleSince + startDelay;
        nextStepAt = idleSince + startDelay;
        stepUntil = -1;
        glancing = false;
        anchored = false;
    }
}
