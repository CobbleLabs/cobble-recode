package cobble.v1_21.compat;

import cobble.render.Renderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Where the legacy drawing code's passes start on 1.21.11, and where what they drew goes out. */
public final class RenderPasses {
    private RenderPasses() {}

    public static void beginWorld(Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        Window window = MinecraftClient.getInstance().getWindow();
        float[] view = new float[16];
        float[] projection = new float[16];
        positionMatrix.get(view);
        projectionMatrix.get(projection);
        GlEmu.beginWorld(view, projection, window.getFramebufferWidth(), window.getFramebufferHeight());
        Vec3d pos = camera.getCameraPos();
        RenderManager.INSTANCE.setCamera(pos.x, pos.y, pos.z, camera.getPitch(), camera.getYaw());
    }

    public static void beginGui() {
        Window window = MinecraftClient.getInstance().getWindow();
        GlEmu.beginGui(window.getScaledWidth(), window.getScaledHeight(), window.getFramebufferWidth(),
                window.getFramebufferHeight());
    }

    public static void flush(Renderer gfx) {
        GlEmu.flush(gfx);
    }
}
