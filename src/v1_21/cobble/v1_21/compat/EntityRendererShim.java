package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;

/** 1.8.9's EntityRenderer on 1.21.11's GameRenderer, which keeps the pointed entity on the client instead. */
public final class EntityRendererShim {
    private EntityRendererShim() {}

    public static Entity pointedEntity(GameRenderer renderer) {
        return MinecraftClient.getInstance().targetedEntity;
    }

    public static void pointedEntity(GameRenderer renderer, Entity entity) {
        MinecraftClient.getInstance().targetedEntity = entity;
    }

    public static void getMouseOver(GameRenderer renderer, float partialTicks) {
        renderer.updateCrosshairTarget(partialTicks);
    }

    /* The spectator shaders 1.8 loaded per entity are 1.21's post effects, which the legacy code never meant. */
    public static void loadEntityShader(GameRenderer renderer, Entity entity) {}
}
