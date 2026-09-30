package cobble.game;

/**
 * Items the modules and scripts ask about by kind. Totems, crystals, anchors
 * and maces do not exist on 1.8.9 and never match; they stay so scripts written
 * against newer versions still load.
 */
public enum ItemKind {
    TOTEM, END_CRYSTAL, RESPAWN_ANCHOR, GLOWSTONE, MACE, OBSIDIAN, AXE, SWORD
}
