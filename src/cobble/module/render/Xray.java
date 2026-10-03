package cobble.module.render;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public final class Xray extends Module {
    private record Choice(Setting setting, List<Block> blocks) {}

    private static volatile Set<Block> shown;
    private static volatile boolean everyFace;

    private final List<Choice> choices = List.of(
            choice("Diamond", Blocks.diamond_ore, Blocks.diamond_block),
            choice("Emerald", Blocks.emerald_ore, Blocks.emerald_block),
            choice("Gold", Blocks.gold_ore, Blocks.gold_block),
            choice("Iron", Blocks.iron_ore, Blocks.iron_block),
            choice("Redstone", Blocks.redstone_ore, Blocks.lit_redstone_ore),
            choice("Lapis", Blocks.lapis_ore, Blocks.lapis_block),
            choice("Coal", Blocks.coal_ore),
            choice("Quartz", Blocks.quartz_ore),
            choice("Spawners", Blocks.mob_spawner),
            choice("Lava", Blocks.lava, Blocks.flowing_lava),
            choice("Water", Blocks.water, Blocks.flowing_water));
    private final Setting caveMode = addBoolean("Cave Mode", "Only show blocks that touch air", false);

    public Xray() {
        super("Xray", "See ores through the ground", Category.VISUALS, Subcategory.ADVANCED);
    }

    private Choice choice(String name, Block... blocks) {
        boolean byDefault = !name.equals("Coal") && !name.equals("Quartz") && !name.equals("Water");
        return new Choice(addBoolean(name, "Show " + name.toLowerCase() + " blocks", byDefault), List.of(blocks));
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
