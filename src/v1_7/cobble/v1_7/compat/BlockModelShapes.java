package cobble.v1_7.compat;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** 1.8.9's block models, of which 1.7.10 only has the icons its blocks draw with. */
public final class BlockModelShapes {
    BlockModelShapes() {}

    /* 1.8.9 answers with the model's particle sprite; the icon on top is 1.7.10's nearest. */
    public TextureAtlasSprite getTexture(IBlockState state) {
        return state.getBlock().getIcon(1, state.metadata()) instanceof TextureAtlasSprite sprite ? sprite : null;
    }
}
