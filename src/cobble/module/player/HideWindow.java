package cobble.module.player;

import cobble.event.GuiOpenEvent;
import cobble.event.LoadWorldEvent;
import cobble.event.PacketReceiveEvent;
import cobble.event.Phase;
import cobble.event.RenderTickEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Subcategory;
import cobble.module.player.hidewindow.FloatingWindowPosition;
import cobble.module.player.hidewindow.FloatingWindowPosition$1;
import cobble.module.player.hidewindow.HideWindowSettings;
import cobble.module.player.hidewindow.SquareBounds;
import cobble.module.player.hidewindow.UKCtdVsMbi;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.network.play.server.S2EPacketCloseWindow;

public class HideWindow extends Module {
    private final HideWindowSettings hideWindowSettings;
    private GuiContainer guiContainer;
    private final FloatingWindowPosition hiddenWindow = new FloatingWindowPosition(0.5F, 0.05F);

    public HideWindow() {
        super("HideWindow", "Keeps a container open while the screen stays in game", Category.PLAYER,
                Subcategory.BASIC);
        this.hideWindowSettings = new HideWindowSettings(this);
    }

    @Override
    public String suffix() {
        return this.isWindowHidden() ? "Hidden" : "";
    }

    public boolean isWindowHidden() {
        return this.guiContainer != null;
    }

    public GuiContainer getHiddenContainer() {
        return this.guiContainer;
    }

    @Override
    public void onDisable(Context context) {
        if (this.guiContainer != null && mc.thePlayer != null) {
            mc.thePlayer.closeScreen();
        }

        this.guiContainer = null;
    }

    @Subscribe(priority = Subscribe.Priority.HIGHEST)
    public void onGuiOpen(GuiOpenEvent var1) {
        if (var1.guiScreen != null && mc.thePlayer != null) {
            if (var1.guiScreen instanceof GuiContainer && !(var1.guiScreen instanceof GuiInventory)) {
                if (mc.currentScreen instanceof GuiContainer) {
                    this.guiContainer = null;
                } else {
                    GuiContainer var2 = (GuiContainer)var1.guiScreen;
                    if (!this.hideWindowSettings.onlyWhileCrouching.bool() || mc.thePlayer.isSneaking()) {
                        this.guiContainer = var2;
                        mc.thePlayer.openContainer = this.guiContainer.inventorySlots;
                        var1.setCancelled(true);
                    }
                }
            } else {
                if (var1.guiScreen instanceof GuiInventory && this.guiContainer != null) {
                    var1.guiScreen = this.guiContainer;
                    this.guiContainer = null;
                }
            }
        }
    }

    @Subscribe
    public void onPacketReceive(PacketReceiveEvent var1) {
        if (var1.getPacket() instanceof S2EPacketCloseWindow) {
            this.guiContainer = null;
        }
    }

    @Subscribe
    public void onLoadWorld(LoadWorldEvent var1) {
        this.guiContainer = null;
    }

    @Subscribe
    public void onRenderTick(RenderTickEvent var1) {
        if (var1.phase == Phase.END) {
            if (this.guiContainer != null && mc.currentScreen == null && !mc.gameSettings.showDebugInfo) {
                this.drawWindowIcon(false);
            }
        }
    }

    public float getWindowPixelX() {
        this.refreshWindowBounds();
        return this.hiddenWindow.getPixelX();
    }

    public float getWindowPixelY() {
        this.refreshWindowBounds();
        return this.hiddenWindow.getPixelY();
    }

    public float yyhw2() {
        this.refreshWindowBounds();
        return this.hiddenWindow.getFractionX();
    }

    public float FJkOz() {
        this.refreshWindowBounds();
        return this.hiddenWindow.getFractionY();
    }

    public void YYiq8(float var1, float var2) {
        ScaledResolution var3 = new ScaledResolution(mc);
        this.hiddenWindow.setFractionPosition(var1, var2, var3.getScaledWidth(), var3.getScaledHeight());
    }

    public void setWindowFractionPosition(float var1, float var2) {
        this.setWindowPixelPosition(var1, var2, new ScaledResolution(mc));
    }

    public void fdma() {
        ScaledResolution var1 = new ScaledResolution(mc);
        this.hiddenWindow.aTls(var1.getScaledWidth(), var1.getScaledHeight());
    }

    public float[] renderIconAndGetBounds(float var1, float var2) {
        FloatingWindowPosition$1 var3 = this.hiddenWindow.createSnapshot();
        this.setWindowFractionPosition(var1, var2);
        this.drawWindowIcon(false);
        int var4 = UKCtdVsMbi.JqzE(this.hideWindowSettings.iconScale.number());
        this.hiddenWindow.LDUKrN(var3);
        return SquareBounds.ZlAtiX(var1, var2, var4, 0.0F);
    }

    private void drawWindowIcon(boolean var1) {
        this.refreshWindowBounds();
        UKCtdVsMbi.ODZybV(this.hiddenWindow, this.hideWindowSettings.iconScale.number(), (this.hideWindowSettings.iconColor.colour().argb() & 0xFFFFFF));
    }

    private void refreshWindowBounds() {
        this.kzE9(new ScaledResolution(mc));
    }

    private void kzE9(ScaledResolution var1) {
        this.hiddenWindow.updateForScreenSize(var1.getScaledWidth(), var1.getScaledHeight());
    }

    private void setWindowPixelPosition(float var1, float var2, ScaledResolution var3) {
        this.hiddenWindow.setPixelPosition(var1, var2, var3.getScaledWidth(), var3.getScaledHeight());
    }
}
