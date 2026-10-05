package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/** 1.8.9's PlayerControllerMP on 1.21.11's ClientPlayerInteractionManager. Everything acts with the main hand. */
public final class PlayerControllerMPShim {
    private PlayerControllerMPShim() {}

    public static int blockHitDelay(ClientPlayerInteractionManager controller) {
        return controller.blockBreakingCooldown;
    }

    public static void blockHitDelay(ClientPlayerInteractionManager controller, int ticks) {
        controller.blockBreakingCooldown = ticks;
    }

    public static float curBlockDamageMP(ClientPlayerInteractionManager controller) {
        return controller.currentBreakingProgress;
    }

    public static void curBlockDamageMP(ClientPlayerInteractionManager controller, float progress) {
        controller.currentBreakingProgress = progress;
    }

    public static BlockPos currentBlock(ClientPlayerInteractionManager controller) {
        return controller.currentBreakingPos;
    }

    public static boolean isHittingBlock(ClientPlayerInteractionManager controller) {
        return controller.breakingBlock;
    }

    public static boolean clickBlock(ClientPlayerInteractionManager controller, BlockPos pos, Direction side) {
        return controller.attackBlock(pos, side);
    }

    public static boolean onPlayerDamageBlock(ClientPlayerInteractionManager controller, BlockPos pos,
                                              Direction side) {
        return controller.updateBlockBreakingProgress(pos, side);
    }

    public static boolean extendedReach(ClientPlayerInteractionManager controller) {
        return controller.getCurrentGameMode().isCreative();
    }

    public static float getBlockReachDistance(ClientPlayerInteractionManager controller) {
        return (float) MinecraftClient.getInstance().player.getBlockInteractionRange();
    }

    public static GameMode getCurrentGameType(ClientPlayerInteractionManager controller) {
        return controller.getCurrentGameMode();
    }

    public static boolean onPlayerRightClick(ClientPlayerInteractionManager controller, ClientPlayerEntity player,
                                             ClientWorld world, ItemStack stack, BlockPos pos, Direction side,
                                             Vec3d hit) {
        return controller.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hit, side, pos, false))
                .isAccepted();
    }

    public static void onStoppedUsingItem(ClientPlayerInteractionManager controller, PlayerEntity player) {
        controller.stopUsingItem(player);
    }

    public static void resetBlockRemoving(ClientPlayerInteractionManager controller) {
        controller.cancelBlockBreaking();
    }

    public static boolean sendUseItem(ClientPlayerInteractionManager controller, PlayerEntity player, World world,
                                      ItemStack stack) {
        return controller.interactItem(player, Hand.MAIN_HAND).isAccepted();
    }

    /* What 1.21's private syncSelectedSlot does. */
    public static void syncCurrentPlayItem(ClientPlayerInteractionManager controller) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        int slot = player.getInventory().getSelectedSlot();
        if (controller.lastSelectedSlot != slot) {
            controller.lastSelectedSlot = slot;
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    /* 1.8's click modes are numbered in the order 1.21 declares its slot actions; 1.21 reports no clicked stack. */
    public static ItemStack windowClick(ClientPlayerInteractionManager controller, int windowId, int slot,
                                        int button, int mode, PlayerEntity player) {
        controller.clickSlot(windowId, slot, button, SlotActionType.values()[mode], player);
        return null;
    }
}
