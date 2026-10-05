package cobble.v1_21.compat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import java.io.File;
import java.lang.reflect.Method;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;

/** 1.8.9's Minecraft on 1.21.11's MinecraftClient. */
public final class MinecraftShim {
    /* The click handlers are private; they run under their intermediary names, which the build leaves in strings. */
    private static final Method DO_ATTACK = handler("method_1536");
    private static final Method DO_ITEM_USE = handler("method_1583");

    private MinecraftShim() {}

    private static Method handler(String name) {
        try {
            Method method = MinecraftClient.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("MinecraftClient." + name + " not found", e);
        }
    }

    public static MinecraftClient getMinecraft() {
        return MinecraftClient.getInstance();
    }

    public static ClientPlayerEntity thePlayer(MinecraftClient mc) {
        return mc.player;
    }

    public static ClientWorld theWorld(MinecraftClient mc) {
        return mc.world;
    }

    public static GameOptions gameSettings(MinecraftClient mc) {
        return mc.options;
    }

    public static ClientPlayerInteractionManager playerController(MinecraftClient mc) {
        return mc.interactionManager;
    }

    public static HitResult objectMouseOver(MinecraftClient mc) {
        return mc.crosshairTarget;
    }

    public static void objectMouseOver(MinecraftClient mc, HitResult target) {
        mc.crosshairTarget = target;
    }

    public static Entity pointedEntity(MinecraftClient mc) {
        return mc.targetedEntity;
    }

    public static void pointedEntity(MinecraftClient mc, Entity entity) {
        mc.targetedEntity = entity;
    }

    public static int leftClickCounter(MinecraftClient mc) {
        return mc.attackCooldown;
    }

    public static void leftClickCounter(MinecraftClient mc, int ticks) {
        mc.attackCooldown = ticks;
    }

    public static int rightClickDelayTimer(MinecraftClient mc) {
        return mc.itemUseCooldown;
    }

    public static void rightClickDelayTimer(MinecraftClient mc, int ticks) {
        mc.itemUseCooldown = ticks;
    }

    public static boolean inGameHasFocus(MinecraftClient mc) {
        return mc.mouse.isCursorLocked();
    }

    public static int displayWidth(MinecraftClient mc) {
        return mc.getWindow().getFramebufferWidth();
    }

    public static int displayHeight(MinecraftClient mc) {
        return mc.getWindow().getFramebufferHeight();
    }

    public static File mcDataDir(MinecraftClient mc) {
        return mc.runDirectory;
    }

    public static RenderManager getRenderManager(MinecraftClient mc) {
        return RenderManager.INSTANCE;
    }

    public static FontRenderer fontRendererObj(MinecraftClient mc) {
        return FontRenderer.INSTANCE;
    }

    public static TextureManager getTextureManager(MinecraftClient mc) {
        return TextureManager.INSTANCE;
    }

    public static RenderItem getRenderItem(MinecraftClient mc) {
        return RenderItem.INSTANCE;
    }

    public static GameRenderer entityRenderer(MinecraftClient mc) {
        return mc.gameRenderer;
    }

    public static net.minecraft.client.render.WorldRenderer renderGlobal(MinecraftClient mc) {
        return mc.worldRenderer;
    }

    public static RenderTickCounter timer(MinecraftClient mc) {
        return mc.getRenderTickCounter();
    }

    public static InGameHud ingameGUI(MinecraftClient mc) {
        return mc.inGameHud;
    }

    public static Mouse mouseHelper(MinecraftClient mc) {
        return mc.mouse;
    }

    public static ClientPlayNetworkHandler getNetHandler(MinecraftClient mc) {
        return mc.getNetworkHandler();
    }

    public static Entity getRenderViewEntity(MinecraftClient mc) {
        return mc.getCameraEntity();
    }

    public static void setRenderViewEntity(MinecraftClient mc, Entity entity) {
        mc.setCameraEntity(entity);
    }

    public static void displayGuiScreen(MinecraftClient mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static int getDebugFPS() {
        return MinecraftClient.getInstance().getCurrentFps();
    }

    public static ServerInfo getCurrentServerData(MinecraftClient mc) {
        return mc.getCurrentServerEntry();
    }

    public static boolean isSingleplayer(MinecraftClient mc) {
        return mc.isInSingleplayer();
    }

    public static boolean isGamePaused(MinecraftClient mc) {
        return mc.isPaused();
    }

    public static boolean isCallingFromMinecraftThread(MinecraftClient mc) {
        return mc.isOnThread();
    }

    public static ListenableFuture<Object> addScheduledTask(MinecraftClient mc, Runnable task) {
        ListenableFutureTask<Object> future = ListenableFutureTask.create(task, null);
        mc.execute(future);
        return future;
    }

    public static void clickMouse(MinecraftClient mc) {
        invoke(DO_ATTACK, mc);
    }

    public static void rightClickMouse(MinecraftClient mc) {
        invoke(DO_ITEM_USE, mc);
    }

    private static void invoke(Method method, MinecraftClient mc) {
        try {
            method.invoke(mc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method.getName() + " failed", e);
        }
    }
}
