package cobble.v1_8;

import cobble.legacy.LegacyGame;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

final class GameAdapter extends LegacyGame {
    private Map<Integer, String> potionNames;
    private Map<Integer, String> enchantmentNames;

    /* 1.8.9 already names its potions and enchantments the way the later
     * registries do, so scripts see the same ids on every version. */
    @Override
    protected String potionName(int id) {
        if (potionNames == null) {
            Map<Integer, String> names = new HashMap<>();
            for (ResourceLocation location : Potion.getPotionLocations()) {
                Potion potion = Potion.getPotionFromResourceLocation(location.toString());
                if (potion != null) {
                    names.put(potion.getId(), location.getResourcePath());
                }
            }
            potionNames = names;
        }
        return potionNames.get(id);
    }

    @Override
    protected String enchantmentName(int id) {
        if (enchantmentNames == null) {
            Map<Integer, String> names = new HashMap<>();
            for (ResourceLocation location : Enchantment.func_181077_c()) {
                Enchantment enchantment = Enchantment.getEnchantmentByLocation(location.toString());
                if (enchantment != null) {
                    names.put(enchantment.effectId, location.getResourcePath());
                }
            }
            enchantmentNames = names;
        }
        return enchantmentNames.get(id);
    }

    /**
     * The icon the item's model uses, out of the block atlas items share on
     * 1.8.9. Items without a model of their own get the missing sprite back,
     * which is reported as no sprite.
     */
    @Override
    public SpriteView itemSprite(String itemId) {
        Item item = Item.getByNameOrId(itemId);
        if (item == null) {
            return SpriteView.NONE;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TextureMap atlas = mc.getTextureMapBlocks();
        TextureAtlasSprite sprite = mc.getRenderItem().getItemModelMesher().getParticleIcon(item);
        if (sprite == null || sprite == atlas.getMissingSprite()) {
            return SpriteView.NONE;
        }
        return new SpriteView(true, atlas.getGlTextureId(),
                sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV());
    }
}
