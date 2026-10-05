package cobble.v1_21.compat;

import net.minecraft.client.render.WorldRenderer;

/** 1.8.9's RenderGlobal on 1.21.11's WorldRenderer. */
public final class RenderGlobalShim {
    private RenderGlobalShim() {}

    public static void loadRenderers(WorldRenderer renderer) {
        renderer.reload();
    }

    /* 1.21 rebuilds entity draws every frame; there is no list to mark. */
    public static void setDisplayListEntitiesDirty(WorldRenderer renderer) {}
}
