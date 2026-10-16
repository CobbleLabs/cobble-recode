package cobble.game;

/**
 * Items the modules and scripts ask about by kind. Totems, crystals, anchors,
 * maces and wind charges do not exist on 1.8.9 and never match; they stay so
 * scripts written against newer versions still load. Glowstone is the block,
 * which is what charges an anchor; the dust does nothing to one. A building
 * block is any full cube that stays where it is put and holds nothing: what a
 * bridge is made of.
 */
public enum ItemKind {
    TOTEM, END_CRYSTAL, RESPAWN_ANCHOR, GLOWSTONE, MACE, OBSIDIAN, AXE, SWORD, WIND_CHARGE, ENDER_PEARL,
    IRON_INGOT, BLAZE_POWDER, GHAST_TEAR, SUGAR, FEATHER, MAGMA_CREAM, SPIDER_EYE, GOLDEN_SWORD, BUILDING_BLOCK
}
