package cobble.v1_7;

import cobble.module.render.Xray;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

/**
 * The handlers whose 1.7.10 hook passes something other than 1.8.9's; every
 * other hook runs the legacy handler in {@link cobble.hook.Hooks}.
 */
public final class Hooks {
    /* Sky and block light both at 15, packed the way the brightness lookup returns them. */
    private static final int FULL_BRIGHT = 15 << 20 | 15 << 4;

    private Hooks() {}

    /* 1.7.10 renders the world in one pass, so there is no pass number to pass on. */
    public static void renderWorldLast(float partialTicks, long finishTime) {
        cobble.hook.Hooks.renderWorldLast(0, partialTicks, finishTime);
    }

    public static boolean renderBlock(Block block, int x, int y, int z) {
        return Xray.hides(block);
    }

    public static int blockLight(IBlockAccess access, int x, int y, int z, int minimum) {
        return Xray.active() ? FULL_BRIGHT : access.getLightBrightnessForSkyBlocks(x, y, z, minimum);
    }
}
