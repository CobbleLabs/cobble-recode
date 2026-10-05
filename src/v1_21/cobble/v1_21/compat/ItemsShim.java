package cobble.v1_21.compat;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * 1.8.9's Items on 1.21.11's. An item 1.8 told apart by metadata is one item of its own here;
 * those take the plainest member of their family.
 */
public final class ItemsShim {
    private ItemsShim() {}

    public static Item apple() {
        return Items.APPLE;
    }

    public static Item arrow() {
        return Items.ARROW;
    }

    public static Item bed() {
        return Items.RED_BED;
    }

    public static Item blaze_rod() {
        return Items.BLAZE_ROD;
    }

    public static Item boat() {
        return Items.OAK_BOAT;
    }

    public static Item bone() {
        return Items.BONE;
    }

    public static Item book() {
        return Items.BOOK;
    }

    public static net.minecraft.item.BowItem bow() {
        return (net.minecraft.item.BowItem) Items.BOW;
    }

    public static Item bowl() {
        return Items.BOWL;
    }

    public static Item bucket() {
        return Items.BUCKET;
    }

    public static Item carrot() {
        return Items.CARROT;
    }

    public static Item carrot_on_a_stick() {
        return Items.CARROT_ON_A_STICK;
    }

    public static Item chainmail_boots() {
        return Items.CHAINMAIL_BOOTS;
    }

    public static Item clock() {
        return Items.CLOCK;
    }

    public static Item compass() {
        return Items.COMPASS;
    }

    public static Item cooked_beef() {
        return Items.COOKED_BEEF;
    }

    public static Item cooked_chicken() {
        return Items.COOKED_CHICKEN;
    }

    public static Item cooked_fish() {
        return Items.COOKED_COD;
    }

    public static Item cookie() {
        return Items.COOKIE;
    }

    public static Item diamond() {
        return Items.DIAMOND;
    }

    public static Item diamond_axe() {
        return Items.DIAMOND_AXE;
    }

    public static Item diamond_boots() {
        return Items.DIAMOND_BOOTS;
    }

    public static Item diamond_hoe() {
        return Items.DIAMOND_HOE;
    }

    public static Item diamond_pickaxe() {
        return Items.DIAMOND_PICKAXE;
    }

    public static Item diamond_shovel() {
        return Items.DIAMOND_SHOVEL;
    }

    public static Item diamond_sword() {
        return Items.DIAMOND_SWORD;
    }

    public static Item dye() {
        return Items.INK_SAC;
    }

    public static Item egg() {
        return Items.EGG;
    }

    public static Item emerald() {
        return Items.EMERALD;
    }

    public static Item ender_pearl() {
        return Items.ENDER_PEARL;
    }

    public static Item fire_charge() {
        return Items.FIRE_CHARGE;
    }

    public static Item fish() {
        return Items.COD;
    }

    public static net.minecraft.item.FishingRodItem fishing_rod() {
        return (net.minecraft.item.FishingRodItem) Items.FISHING_ROD;
    }

    public static Item flint_and_steel() {
        return Items.FLINT_AND_STEEL;
    }

    public static Item glowstone_dust() {
        return Items.GLOWSTONE_DUST;
    }

    public static Item gold_ingot() {
        return Items.GOLD_INGOT;
    }

    public static Item golden_apple() {
        return Items.GOLDEN_APPLE;
    }

    public static Item golden_carrot() {
        return Items.GOLDEN_CARROT;
    }

    public static Item golden_hoe() {
        return Items.GOLDEN_HOE;
    }

    public static Item golden_pickaxe() {
        return Items.GOLDEN_PICKAXE;
    }

    public static Item golden_sword() {
        return Items.GOLDEN_SWORD;
    }

    public static Item iron_boots() {
        return Items.IRON_BOOTS;
    }

    public static Item iron_chestplate() {
        return Items.IRON_CHESTPLATE;
    }

    public static Item iron_hoe() {
        return Items.IRON_HOE;
    }

    public static Item iron_ingot() {
        return Items.IRON_INGOT;
    }

    public static Item iron_pickaxe() {
        return Items.IRON_PICKAXE;
    }

    public static Item iron_shovel() {
        return Items.IRON_SHOVEL;
    }

    public static Item iron_sword() {
        return Items.IRON_SWORD;
    }

    public static Item lava_bucket() {
        return Items.LAVA_BUCKET;
    }

    public static Item milk_bucket() {
        return Items.MILK_BUCKET;
    }

    public static Item mushroom_stew() {
        return Items.MUSHROOM_STEW;
    }

    public static Item name_tag() {
        return Items.NAME_TAG;
    }

    public static Item nether_star() {
        return Items.NETHER_STAR;
    }

    public static Item netherbrick() {
        return Items.NETHER_BRICK;
    }

    public static Item prismarine_shard() {
        return Items.PRISMARINE_SHARD;
    }

    public static Item pumpkin_pie() {
        return Items.PUMPKIN_PIE;
    }

    public static Item quartz() {
        return Items.QUARTZ;
    }

    public static net.minecraft.item.ShearsItem shears() {
        return (net.minecraft.item.ShearsItem) Items.SHEARS;
    }

    public static Item sign() {
        return Items.OAK_SIGN;
    }

    public static Item snowball() {
        return Items.SNOWBALL;
    }

    public static Item speckled_melon() {
        return Items.GLISTERING_MELON_SLICE;
    }

    public static Item stick() {
        return Items.STICK;
    }

    public static Item stone_hoe() {
        return Items.STONE_HOE;
    }

    public static Item stone_shovel() {
        return Items.STONE_SHOVEL;
    }

    public static Item stone_sword() {
        return Items.STONE_SWORD;
    }

    public static Item sugar() {
        return Items.SUGAR;
    }

    public static Item water_bucket() {
        return Items.WATER_BUCKET;
    }

    public static Item wooden_axe() {
        return Items.WOODEN_AXE;
    }

    public static Item wooden_hoe() {
        return Items.WOODEN_HOE;
    }

    public static Item wooden_pickaxe() {
        return Items.WOODEN_PICKAXE;
    }

    public static Item wooden_sword() {
        return Items.WOODEN_SWORD;
    }
}
