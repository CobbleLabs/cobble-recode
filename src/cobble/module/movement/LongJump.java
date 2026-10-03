package cobble.module.movement;

import cobble.event.MoveEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.ui.Client;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;

/**
 * Jumps far: the take-off gets a burst of the boost times ground speed, and
 * the speed bleeds off through the air from there. Toggle turns it off on
 * landing, so one press is one jump.
 */
public final class LongJump extends Module {
    /* Where airborne speed settles right after take-off, before any Speed effect. */
    private static final double AIR_SPEED = 0.3303950079529733;

    private final Setting boost = addNumber("Boost", "How much faster the take-off is than walking", 4.1f, 3.0f, 5.0f,
            1);
    private final Setting toggle = addBoolean("Toggle", "Turn off after touching the ground", true);

    private double moveSpeed;
    private double lastDistance;
    private boolean wasOnGround;
    private boolean jumpStarted;

    public LongJump() {
        super("LongJump", "Jumps much further than normal", Category.MOVEMENT, Subcategory.ADVANCED);
    }

    @Override
    public void onEnable(Context context) {
        moveSpeed = 0.28;
        lastDistance = 0.0;
        wasOnGround = false;
        jumpStarted = false;
    }

    @Subscribe
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        lastDistance = Math.hypot(player.posX - player.prevPosX, player.posZ - player.prevPosZ);
        if (!toggle.bool()) {
            return;
        }
        if (!wasOnGround && player.isCollidedVertically && jumpStarted) {
            setEnabled(false, Client.context());
        } else if (!jumpStarted && movementHeld(player)) {
            jumpStarted = true;
        }
    }

    @Subscribe
    public void onMove(MoveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (!Strafing.moving(player) || player.isInWater()) {
            moveSpeed = Strafing.baseSpeed(player, 0.15, 5);
            return;
        }
        double base = Strafing.baseSpeed(player, 0.15, 5) * surfaceFactor(player);
        moveSpeed = base;
        if (player.onGround) {
            if (wasOnGround) {
                moveSpeed = base * boost.number();
                player.motionY = 0.42f;
                event.setY(0.42f);
            }
        } else if (wasOnGround) {
            double airSpeed = AIR_SPEED * Strafing.baseSpeed(player, 0.15, 5) / Strafing.BASE_SPEED;
            moveSpeed = lastDistance - 0.666 * (lastDistance - airSpeed);
            jumpStarted = true;
        } else {
            moveSpeed = lastDistance - lastDistance / 50.0;
        }
        wasOnGround = player.onGround;
        Strafing.move(event, Math.max(moveSpeed, base));
    }

    /* Vines, sneaking and liquid slow the take-off; ice speeds it; no sprint without food. */
    private double surfaceFactor(EntityPlayerSP player) {
        if (player.isInsideOfMaterial(Material.vine)) {
            return 0.5;
        }
        if (player.isSneaking()) {
            return 0.8;
        }
        if (player.isInLava() || player.isInWater()) {
            return 0.54;
        }
        float slipperiness = mc.theWorld.getBlockState(new BlockPos(player.posX, player.posY - 0.1, player.posZ))
                .getBlock().slipperiness;
        if (slipperiness == 0.98f) {
            return 2.4;
        }
        return player.getFoodStats().getFoodLevel() >= 6 ? 1.0 : 0.765;
    }

    private boolean movementHeld(EntityPlayerSP player) {
        return mc.gameSettings.keyBindForward.isKeyDown() || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown() || mc.gameSettings.keyBindRight.isKeyDown()
                || player.movementInput.moveForward != 0.0f || player.movementInput.moveStrafe != 0.0f;
    }
}
