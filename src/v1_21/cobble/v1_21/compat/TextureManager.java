package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.Identifier;

/**
 * 1.8.9's TextureManager for the legacy drawing code, over the game's:
 * binding a location makes its GL texture the one {@link GlEmu} samples.
 */
public final class TextureManager {
    static final TextureManager INSTANCE = new TextureManager();

    private int registered;

    private TextureManager() {}

    public void bindTexture(Identifier id) {
        AbstractTexture texture = MinecraftClient.getInstance().getTextureManager().getTexture(id);
        GlEmu.bindTexture(texture != null && texture.getGlTexture() instanceof GlTexture gl ? gl.getGlId() : 0);
    }

    public Identifier getDynamicTextureLocation(String name, DynamicTexture texture) {
        Identifier id = Identifier.of("cobble", "dynamic/" + name.toLowerCase().replaceAll("[^a-z0-9_.-]", "_") + "_"
                + registered++);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture.backing);
        return id;
    }
}
