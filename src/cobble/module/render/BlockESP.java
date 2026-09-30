package cobble.module.render;

import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.render.blockesp.BedWarsDetector;
import cobble.module.render.blockesp.BlockEspRenderer;
import cobble.module.render.blockesp.BlockEspRenderer$0;
import cobble.module.render.blockesp.BlockEspScanLimits;
import cobble.module.render.blockesp.BlockEspSettings;
import cobble.util.ClientUtils;
import cobble.util.block.BlockScanner;

public class BlockESP extends Module {
    private final BlockEspSettings blockEspSettings = new BlockEspSettings(this);
    private int lastEntryHash;

    public BlockESP() {
        super("BlockESP", "Highlights chosen blocks", Category.VISUALS);
    }

    @Override
    public void onEnable(Context context) {
        BlockScanner var1 = BlockScanner.getInstance();
        var1.hokXq4(this.blockEspSettings.blocks);
        this.lastEntryHash = this.blockEspSettings.blocks.getEntries().hashCode();
        if (!this.blockEspSettings.blocks.getEntries().isEmpty()) {
            var1.Ziwu();
        }
    }

    @Override
    public void onDisable(Context context) {
        BlockScanner.getInstance().vUnwA();
    }

    @Override
    public void onUpdate() {
        if (ClientUtils.isInWorld()) {
            int var1 = this.blockEspSettings.blocks.getEntries().hashCode();
            if (var1 != this.lastEntryHash) {
                this.lastEntryHash = var1;
                BlockScanner.getInstance().Ziwu();
            }
        }
    }

    public int getSearchSpeed() {
        boolean var1 = !this.blockEspSettings.gameCheck.bool() || BedWarsDetector.isBedWarsGame();
        return BlockEspScanLimits.resolveBlocksPerTick(this.enabled(), !this.blockEspSettings.blocks.getEntries().isEmpty(), var1, this.blockEspSettings.searchSpeed.number());
    }

    @Override
    public String suffix() {
        int var1 = BlockScanner.getInstance().getTrackedBlockCount();
        return var1 > 0 ? String.valueOf(var1) : "";
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent var1) {
        BlockScanner var2 = BlockScanner.getInstance();
        if (var2.isScanningActive() && ClientUtils.isInWorld()) {
            if (!this.blockEspSettings.gameCheck.bool() || BedWarsDetector.isBedWarsGame()) {
                double var3 = this.blockEspSettings.range.number();
                BlockEspRenderer$0 var5 = new BlockEspRenderer$0(
                    var3 * var3, (int)this.blockEspSettings.maxBlocksRendered.number(), this.getMaxPerChunk(), this.blockEspSettings.outline.bool(), this.blockEspSettings.shade.bool()
                );
                BlockEspRenderer.renderNearbyBlocks(mc, var2, this.blockEspSettings.blocks, var5);
            }
        }
    }

    private int getMaxPerChunk() {
        return BlockEspScanLimits.resolveMaxPerChunk(this.blockEspSettings.maxPerChunk.number());
    }
}
