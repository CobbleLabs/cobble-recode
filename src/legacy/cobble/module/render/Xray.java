package cobble.module.render;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Builds the world without everything but the chosen blocks. The chunk
 * builder skips the rest, the kept blocks draw every face instead of only the
 * ones against air, and they are lit at full brightness: an ore buried in
 * stone has no light of its own and would otherwise come out black. Cave mode
 * keeps the game's own face culling instead, so only blocks that touch air
 * show: the ores a cave has opened up.
 *
 * <p>Chunks are built on the game's worker threads, so the hooks read one
 * immutable snapshot of what is shown rather than the settings.
 */
public final class Xray extends LegacyModule {
    private record Choice(Setting setting, List<Block> blocks) {}

    private static volatile Set<Block> shown;
    private static volatile boolean everyFace;

    /* By name, each version's spelling and the deepslate ores side by side; a choice none of whose blocks the version has is left out. */
    private final List<Choice> choices = Stream.of(
            choice("Diamond", "diamond_ore", "deepslate_diamond_ore", "diamond_block"),
            choice("Emerald", "emerald_ore", "deepslate_emerald_ore", "emerald_block"),
            choice("Gold", "gold_ore", "deepslate_gold_ore", "nether_gold_ore", "gold_block"),
            choice("Iron", "iron_ore", "deepslate_iron_ore", "iron_block"),
            choice("Copper", "copper_ore", "deepslate_copper_ore"),
            choice("Redstone", "redstone_ore", "lit_redstone_ore", "deepslate_redstone_ore"),
            choice("Lapis", "lapis_ore", "deepslate_lapis_ore", "lapis_block"),
            choice("Coal", "coal_ore", "deepslate_coal_ore"),
            choice("Quartz", "quartz_ore", "nether_quartz_ore"),
            choice("Ancient Debris", "ancient_debris"),
            choice("Spawners", "mob_spawner", "spawner"),
            choice("Lava", "lava", "flowing_lava"),
            choice("Water", "water", "flowing_water")).filter(Objects::nonNull).toList();
    private final Setting caveMode = addBoolean("Cave Mode", "Only show blocks that touch air", false);

    public Xray() {
        super("Xray", "See ores through the ground", Category.VISUALS, Subcategory.ADVANCED);
    }

    private Choice choice(String name, String... ids) {
        /* 1.7.10 answers a name it has no block for with air rather than null. */
        List<Block> blocks = Stream.of(ids).map(Block::getBlockFromName)
                .filter(block -> block != null && block != Blocks.air).toList();
        if (blocks.isEmpty()) {
            return null;
        }
        boolean byDefault = !name.equals("Coal") && !name.equals("Quartz") && !name.equals("Water");
        return new Choice(addBoolean(name, "Show " + name.toLowerCase() + " blocks", byDefault), blocks);
    }

    /** True while the world is being built for the xray, for the hooks. */
    public static boolean active() {
        return shown != null;
    }

    /** Whether a kept block draws its faces against other blocks too. */
    public static boolean drawsEveryFace() {
        return everyFace;
    }

    /** Whether the chunk builder should leave a block out. */
    public static boolean hides(Block block) {
        Set<Block> snapshot = shown;
        return snapshot != null && !snapshot.contains(block);
    }

    @Override
    public void onEnable(Context context) {
        everyFace = !caveMode.bool();
        shown = selection();
        rebuild();
    }

    @Override
    public void onDisable(Context context) {
        shown = null;
        everyFace = false;
        rebuild();
    }

    @Override
    protected void settingChanged(Setting setting) {
        if (enabled()) {
            everyFace = !caveMode.bool();
            shown = selection();
            rebuild();
        }
    }

    private Set<Block> selection() {
        Set<Block> blocks = new HashSet<>();
        for (Choice choice : choices) {
            if (choice.setting().bool()) {
                blocks.addAll(choice.blocks());
            }
        }
        return Set.copyOf(blocks);
    }

    /* The chunks on screen were built for the old selection; they have to be built again to show the new one. */
    private void rebuild() {
        if (mc.renderGlobal != null && mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }
}
