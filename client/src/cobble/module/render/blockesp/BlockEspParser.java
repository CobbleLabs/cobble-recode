package cobble.module.render.blockesp;

import cobble.util.VCvk14;

public final class BlockEspParser {
    private static final BlockItemIcons blockItemIcons = new BlockItemIcons();
    private static final BlockEspCatalog blockEspCatalog = new BlockEspCatalog(blockItemIcons);

    public static boolean isWildcardPattern(String var0) {
        return VCvk14.hasWildcardSuffix(var0);
    }

    public static String getBaseRegistryName(String var0) {
        return VCvk14.stripWildcardSuffix(var0);
    }
}
