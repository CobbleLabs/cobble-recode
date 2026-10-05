package cobble.v1_21.compat;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/**
 * 1.8.9's Blocks on 1.21.11's. A block 1.8 told apart by metadata, a wood or a colour,
 * is a block of its own here; those take the oak or white member of their family, so a
 * test against one only catches that member.
 */
public final class BlocksShim {
    private BlocksShim() {}

    public static Block air() {
        return Blocks.AIR;
    }

    public static Block barrier() {
        return Blocks.BARRIER;
    }

    public static Block bed() {
        return Blocks.RED_BED;
    }

    public static Block bedrock() {
        return Blocks.BEDROCK;
    }

    public static net.minecraft.block.ChestBlock chest() {
        return (net.minecraft.block.ChestBlock) Blocks.CHEST;
    }

    public static Block coal_ore() {
        return Blocks.COAL_ORE;
    }

    public static Block cobblestone() {
        return Blocks.COBBLESTONE;
    }

    public static Block command_block() {
        return Blocks.COMMAND_BLOCK;
    }

    public static Block crafting_table() {
        return Blocks.CRAFTING_TABLE;
    }

    public static net.minecraft.block.DryVegetationBlock deadbush() {
        return (net.minecraft.block.DryVegetationBlock) Blocks.DEAD_BUSH;
    }

    public static Block diamond_block() {
        return Blocks.DIAMOND_BLOCK;
    }

    public static Block diamond_ore() {
        return Blocks.DIAMOND_ORE;
    }

    public static Block dirt() {
        return Blocks.DIRT;
    }

    public static Block dispenser() {
        return Blocks.DISPENSER;
    }

    public static net.minecraft.block.TallPlantBlock double_plant() {
        return (net.minecraft.block.TallPlantBlock) Blocks.TALL_GRASS;
    }

    public static Block dropper() {
        return Blocks.DROPPER;
    }

    public static Block emerald_block() {
        return Blocks.EMERALD_BLOCK;
    }

    public static Block emerald_ore() {
        return Blocks.EMERALD_ORE;
    }

    public static Block end_stone() {
        return Blocks.END_STONE;
    }

    public static Block ender_chest() {
        return Blocks.ENDER_CHEST;
    }

    public static net.minecraft.block.FireBlock fire() {
        return (net.minecraft.block.FireBlock) Blocks.FIRE;
    }

    public static net.minecraft.block.FluidBlock flowing_lava() {
        return (net.minecraft.block.FluidBlock) Blocks.LAVA;
    }

    public static net.minecraft.block.FluidBlock flowing_water() {
        return (net.minecraft.block.FluidBlock) Blocks.WATER;
    }

    public static Block furnace() {
        return Blocks.FURNACE;
    }

    public static Block glass() {
        return Blocks.GLASS;
    }

    public static Block glowstone() {
        return Blocks.GLOWSTONE;
    }

    public static Block gold_block() {
        return Blocks.GOLD_BLOCK;
    }

    public static Block gold_ore() {
        return Blocks.GOLD_ORE;
    }

    public static Block gravel() {
        return Blocks.GRAVEL;
    }

    public static Block hardened_clay() {
        return Blocks.TERRACOTTA;
    }

    public static Block hay_block() {
        return Blocks.HAY_BLOCK;
    }

    public static Block ice() {
        return Blocks.ICE;
    }

    public static Block iron_block() {
        return Blocks.IRON_BLOCK;
    }

    public static Block iron_ore() {
        return Blocks.IRON_ORE;
    }

    public static Block ladder() {
        return Blocks.LADDER;
    }

    public static Block lapis_block() {
        return Blocks.LAPIS_BLOCK;
    }

    public static Block lapis_ore() {
        return Blocks.LAPIS_ORE;
    }

    public static net.minecraft.block.FluidBlock lava() {
        return (net.minecraft.block.FluidBlock) Blocks.LAVA;
    }

    public static Block lit_redstone_ore() {
        return Blocks.REDSTONE_ORE;
    }

    public static Block log() {
        return Blocks.OAK_LOG;
    }

    public static Block log2() {
        return Blocks.ACACIA_LOG;
    }

    public static Block mob_spawner() {
        return Blocks.SPAWNER;
    }

    public static Block noteblock() {
        return Blocks.NOTE_BLOCK;
    }

    public static Block obsidian() {
        return Blocks.OBSIDIAN;
    }

    public static Block packed_ice() {
        return Blocks.PACKED_ICE;
    }

    public static Block planks() {
        return Blocks.OAK_PLANKS;
    }

    public static Block quartz_ore() {
        return Blocks.NETHER_QUARTZ_ORE;
    }

    public static Block redstone_ore() {
        return Blocks.REDSTONE_ORE;
    }

    public static net.minecraft.block.ColoredFallingBlock sand() {
        return (net.minecraft.block.ColoredFallingBlock) Blocks.SAND;
    }

    public static Block slime_block() {
        return Blocks.SLIME_BLOCK;
    }

    public static Block soul_sand() {
        return Blocks.SOUL_SAND;
    }

    public static Block sponge() {
        return Blocks.SPONGE;
    }

    public static net.minecraft.block.StainedGlassBlock stained_glass() {
        return (net.minecraft.block.StainedGlassBlock) Blocks.WHITE_STAINED_GLASS;
    }

    public static Block stained_hardened_clay() {
        return Blocks.WHITE_TERRACOTTA;
    }

    public static Block stone() {
        return Blocks.STONE;
    }

    public static Block stonebrick() {
        return Blocks.STONE_BRICKS;
    }

    public static Block tnt() {
        return Blocks.TNT;
    }

    public static Block trapped_chest() {
        return Blocks.TRAPPED_CHEST;
    }

    public static Block vine() {
        return Blocks.VINE;
    }

    public static net.minecraft.block.FluidBlock water() {
        return (net.minecraft.block.FluidBlock) Blocks.WATER;
    }

    public static Block web() {
        return Blocks.COBWEB;
    }

    public static Block wool() {
        return Blocks.WHITE_WOOL;
    }
}
