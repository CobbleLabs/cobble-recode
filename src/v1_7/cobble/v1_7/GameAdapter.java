package cobble.v1_7;

import cobble.legacy.LegacyGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.util.IIcon;

final class GameAdapter extends LegacyGame {
    /* The registry paths 1.8.9 gives the same ids, so scripts see one name on every version. */
    private static final String[] POTIONS = {
        null, "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
        "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility",
        "blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost", "absorption",
        "saturation",
    };
    private static final String[] ENCHANTMENTS = new String[63];

    static {
        String[] named = {
            "0", "protection", "1", "fire_protection", "2", "feather_falling", "3", "blast_protection",
            "4", "projectile_protection", "5", "respiration", "6", "aqua_affinity", "7", "thorns",
            "16", "sharpness", "17", "smite", "18", "bane_of_arthropods", "19", "knockback", "20", "fire_aspect",
            "21", "looting", "32", "efficiency", "33", "silk_touch", "34", "unbreaking", "35", "fortune",
            "48", "power", "49", "punch", "50", "flame", "51", "infinity", "61", "luck_of_the_sea", "62", "lure",
        };
        for (int index = 0; index < named.length; index += 2) {
            ENCHANTMENTS[Integer.parseInt(named[index])] = named[index + 1];
        }
    }

    @Override
    protected String potionName(int id) {
        return id >= 0 && id < POTIONS.length ? POTIONS[id] : null;
    }

    @Override
    protected String enchantmentName(int id) {
        return id >= 0 && id < ENCHANTMENTS.length ? ENCHANTMENTS[id] : null;
    }

    /** The item's own icon, out of whichever of 1.7.10's two atlases it draws from. */
    @Override
    public SpriteView itemSprite(String itemId) {
        Item item = (Item) Item.itemRegistry.getObject(itemId.indexOf(':') >= 0 ? itemId : "minecraft:" + itemId);
        if (item == null) {
            return SpriteView.NONE;
        }
        IIcon icon = item.getIconFromDamage(0);
        if (icon == null) {
            return SpriteView.NONE;
        }
        ITextureObject atlas = Minecraft.getMinecraft().getTextureManager().getTexture(
                item.getSpriteNumber() == 0 ? TextureMap.locationBlocksTexture : TextureMap.locationItemsTexture);
        if (atlas == null) {
            return SpriteView.NONE;
        }
        return new SpriteView(true, atlas.getGlTextureId(),
                icon.getMinU(), icon.getMinV(), icon.getMaxU(), icon.getMaxV());
    }
}
