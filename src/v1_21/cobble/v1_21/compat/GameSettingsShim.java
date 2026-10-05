package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;

/** 1.8.9's GameSettings on 1.21.11's GameOptions, whose settings are option objects. */
public final class GameSettingsShim {
    private GameSettingsShim() {}

    public static KeyBinding keyBindAttack(GameOptions options) {
        return options.attackKey;
    }

    public static KeyBinding keyBindUseItem(GameOptions options) {
        return options.useKey;
    }

    public static KeyBinding keyBindForward(GameOptions options) {
        return options.forwardKey;
    }

    public static KeyBinding keyBindBack(GameOptions options) {
        return options.backKey;
    }

    public static KeyBinding keyBindLeft(GameOptions options) {
        return options.leftKey;
    }

    public static KeyBinding keyBindRight(GameOptions options) {
        return options.rightKey;
    }

    public static KeyBinding keyBindJump(GameOptions options) {
        return options.jumpKey;
    }

    public static KeyBinding keyBindSneak(GameOptions options) {
        return options.sneakKey;
    }

    public static KeyBinding keyBindSprint(GameOptions options) {
        return options.sprintKey;
    }

    public static float fovSetting(GameOptions options) {
        return options.getFov().getValue();
    }

    /* 1.21's slider runs from 30 to 110 and refuses anything past it. */
    public static void fovSetting(GameOptions options, float fov) {
        options.getFov().setValue(Math.round(Math.max(30.0f, Math.min(110.0f, fov))));
    }

    public static float gammaSetting(GameOptions options) {
        return options.getGamma().getValue().floatValue();
    }

    /* Written past the option's validation: 1.8 let gamma go well beyond the slider, which fullbright relies on. */
    public static void gammaSetting(GameOptions options, float gamma) {
        options.getGamma().value = (double) gamma;
    }

    public static float mouseSensitivity(GameOptions options) {
        return options.getMouseSensitivity().getValue().floatValue();
    }

    public static void mouseSensitivity(GameOptions options, float sensitivity) {
        options.getMouseSensitivity().setValue((double) sensitivity);
    }

    public static boolean showDebugInfo(GameOptions options) {
        return MinecraftClient.getInstance().getDebugHud().shouldShowDebugHud();
    }

    public static int renderDistanceChunks(GameOptions options) {
        return options.getViewDistance().getValue();
    }

    /* 1.8's view numbers are the order 1.21 declares its perspectives in. */
    public static int thirdPersonView(GameOptions options) {
        return options.getPerspective().ordinal();
    }

    public static void thirdPersonView(GameOptions options, int view) {
        options.setPerspective(Perspective.values()[view]);
    }

    public static boolean viewBobbing(GameOptions options) {
        return options.getBobView().getValue();
    }

    public static void viewBobbing(GameOptions options, boolean bobbing) {
        options.getBobView().setValue(bobbing);
    }
}
