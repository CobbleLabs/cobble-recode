package cobble.module.render;

import cobble.event.GuiOpenEvent;
import cobble.event.LoadWorldEvent;
import cobble.event.Phase;
import cobble.event.RenderTickEvent;
import cobble.event.Subscribe;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.player.Freecam;
import cobble.module.render.freelook.FreeLookMath;
import cobble.module.render.freelook.KeyStateEdgeDetector;
import cobble.module.render.freelook.PerspectiveController;
import cobble.util.ClientUtils;
import java.util.List;
import net.minecraft.util.MathHelper;

public class FreeLook extends Module {
    private static final String[] ElF = new String[]{"Toggle", "Hold"};
    public static boolean DnH;
    public static float savedYaw;
    public static float savedPitch;
    private Setting mode;
    private Setting customFov;
    private Setting fov;
    private Setting key;
    private final KeyStateEdgeDetector gseb7 = new KeyStateEdgeDetector();
    private int YFs;
    private float savedFov;

    public FreeLook() {
        super("FreeLook", "Looks around without turning your player", Category.VISUALS);
        this.key = addKey("Key", "", Input.glfwKey(56));
        this.mode = addChoice("Mode", "", List.of(ElF), 1);
        this.customFov = addBoolean("Custom FOV", "", false);
        this.fov = addNumber("FOV", "", 90.0F, 10.0F, 150.0F, 0);
    }

    @Override
    public void guiUpdate() {
        this.fov.setVisible(this.customFov.bool());
    }

    @Subscribe
    public void onRenderTick(RenderTickEvent var1) {
        if (var1.phase == Phase.END && mc.currentScreen == null && ClientUtils.isInWorld()) {
            Module var2 = ModuleManager.get(Freecam.class);
            if (!(var2 instanceof Freecam) || Freecam.cameraEntity == null) {
                Boolean var3 = this.gseb7.pollChange(Input.keyHeld(this.key.key()));
                if (var3 != null) {
                    this.updateFreeLookState(var3);
                }
            }
        }
    }

    @Subscribe
    public void onGuiOpen(GuiOpenEvent var1) {
        if (var1.guiScreen != null && DnH && this.isHoldMode()) {
            this.disableFreeLook();
        }
    }

    @Subscribe
    public void onLoadWorld(LoadWorldEvent var1) {
        if (DnH) {
            this.disableFreeLook();
        }
    }

    private void updateFreeLookState(boolean var1) {
        if (!this.enabled()) {
            if (DnH) {
                this.disableFreeLook();
            }
        } else {
            if (var1) {
                savedYaw = mc.thePlayer.rotationYaw;
                savedPitch = mc.thePlayer.rotationPitch;
                if (DnH) {
                    this.disableFreeLook();
                } else {
                    this.enableFreeLook();
                }
            } else if (this.isHoldMode()) {
                this.disableFreeLook();
            }
        }
    }

    public void enableFreeLook() {
        DnH = true;
        this.YFs = mc.gameSettings.thirdPersonView;
        this.setThirdPersonView(1);
        this.savedFov = mc.gameSettings.fovSetting;
    }

    public void disableFreeLook() {
        DnH = false;
        this.setThirdPersonView(this.YFs);
        if (mc.currentScreen == null && mc.inGameHasFocus) {
            mc.mouseHelper.grabMouseCursor();
        }

        if (FreeLookMath.needsFovRestore(this.isHoldMode(), mc.gameSettings.fovSetting, this.savedFov, this.customFov.bool())) {
            mc.gameSettings.fovSetting = this.savedFov;
        }
    }

    @Override
    public void onDisable(Context context) {
        if (DnH) {
            DnH = false;
            this.setThirdPersonView(0);
            if (mc.currentScreen == null && mc.inGameHasFocus) {
                mc.mouseHelper.grabMouseCursor();
            }

            mc.gameSettings.fovSetting = this.savedFov;
        }
    }

    /** False while free look turns the camera instead, having consumed the mouse movement. */
    public boolean mouseTurnsPlayer() {
        if (!DnH || mc.gameSettings.thirdPersonView == 0) {
            return true;
        }

        mc.mouseHelper.mouseXYChange();
        float var1 = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float var2 = var1 * var1 * var1 * 8.0F * 0.15F;
        savedYaw += mc.mouseHelper.deltaX * var2;
        savedPitch = MathHelper.clamp_float(savedPitch - mc.mouseHelper.deltaY * var2, -90.0F, 90.0F);
        if (this.customFov.bool()) {
            mc.gameSettings.fovSetting = this.fov.number();
        }

        return false;
    }

    private boolean isHoldMode() {
        return (int)this.mode.choice() == 1;
    }

    private void setThirdPersonView(int var1) {
        PerspectiveController.wxaroH(mc, var1);
    }
}
