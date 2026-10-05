package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 1.8.9's EntityPlayerSP on 1.21.11's ClientPlayerEntity. */
public final class EntityPlayerSPShim {
    private EntityPlayerSPShim() {}

    public static float lastReportedYaw(ClientPlayerEntity player) {
        return player.lastYawClient;
    }

    public static float lastReportedPitch(ClientPlayerEntity player) {
        return player.lastPitchClient;
    }

    public static void lastReportedYaw(ClientPlayerEntity player, float value) {
        player.lastYawClient = value;
    }

    public static void lastReportedPitch(ClientPlayerEntity player, float value) {
        player.lastPitchClient = value;
    }

    public static double lastReportedPosX(ClientPlayerEntity player) {
        return player.lastXClient;
    }

    public static double lastReportedPosY(ClientPlayerEntity player) {
        return player.lastYClient;
    }

    public static double lastReportedPosZ(ClientPlayerEntity player) {
        return player.lastZClient;
    }

    public static float renderArmYaw(ClientPlayerEntity player) {
        return player.renderYaw;
    }

    public static void renderArmYaw(ClientPlayerEntity player, float yaw) {
        player.renderYaw = yaw;
    }

    public static float prevRenderArmYaw(ClientPlayerEntity player) {
        return player.lastRenderYaw;
    }

    public static void prevRenderArmYaw(ClientPlayerEntity player, float yaw) {
        player.lastRenderYaw = yaw;
    }

    public static Input movementInput(ClientPlayerEntity player) {
        return player.input;
    }

    public static ClientPlayNetworkHandler sendQueue(ClientPlayerEntity player) {
        return player.networkHandler;
    }

    public static int sprintToggleTimer(ClientPlayerEntity player) {
        return player.ticksLeftToDoubleTapSprint;
    }

    public static void sprintToggleTimer(ClientPlayerEntity player, int ticks) {
        player.ticksLeftToDoubleTapSprint = ticks;
    }

    public static void addChatMessage(ClientPlayerEntity player, Text message) {
        player.sendMessage(message, false);
    }

    public static void closeScreen(ClientPlayerEntity player) {
        player.closeHandledScreen();
    }

    public static Vec3d getLook(ClientPlayerEntity player, float partialTicks) {
        return player.getRotationVec(partialTicks);
    }

    public static BlockPos getPosition(ClientPlayerEntity player) {
        return player.getBlockPos();
    }

    public static boolean isCurrentViewEntity(ClientPlayerEntity player) {
        return MinecraftClient.getInstance().getCameraEntity() == player;
    }

    /* 1.8's sound names mostly name nothing on 1.21; one that does plays, the rest are silent. */
    public static void playSound(ClientPlayerEntity player, String name, float volume, float pitch) {
        Identifier id = Identifier.tryParse(name);
        SoundEvent sound = id == null ? null : Registries.SOUND_EVENT.get(id);
        if (sound != null) {
            player.playSound(sound, volume, pitch);
        }
    }

    public static void respawnPlayer(ClientPlayerEntity player) {
        player.requestRespawn();
    }

    public static void sendChatMessage(ClientPlayerEntity player, String message) {
        if (message.startsWith("/")) {
            player.networkHandler.sendChatCommand(message.substring(1));
        } else {
            player.networkHandler.sendChatMessage(message);
        }
    }

    public static void swingItem(ClientPlayerEntity player) {
        player.swingHand(Hand.MAIN_HAND);
    }
}
