package cobble.game;

/**
 * Items the modules and scripts ask about by kind. Totems, crystals, anchors,
 * maces and wind charges do not exist on 1.8.9 and never match; they stay so
 * scripts written against newer versions still load. Glowstone is the block,
 * which is what charges an anchor; the dust does nothing to one.
 */
public enum ItemKind {
    TOTEM, END_CRYSTAL, RESPAWN_ANCHOR, GLOWSTONE, MACE, OBSIDIAN, AXE, SWORD, WIND_CHARGE, ENDER_PEARL
}
