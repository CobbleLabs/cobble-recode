package cobble.module.render;

import cobble.core.Color;
import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.util.render.RenderUtils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.tileentity.TileEntityDropper;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.tileentity.TileEntityHopper;

/** Boxes chests and other containers through walls, each kind in its own colour; open chests are outlined. */
public final class StorageESP extends Module {
    private final Setting outlineOpen = addBoolean("Outline open", "Outline open chests in a contrasting color", true);
    private final Setting chests = addBoolean("Render Chests", "Box chests", true);
    private final Setting chestColor = addColour("Chest Color", "", Color.rgb(0x01FF92, 100 / 255.0f))
            .visibleWhen(() -> this.chests.bool());
    private final Setting trappedChests = addBoolean("Render Trapped Chests", "Box trapped chests", true);
    private final Setting trappedChestColor = addColour("Trapped Chest Color", "", Color.rgb(0xFF0000, 100 / 255.0f))
            .visibleWhen(() -> this.trappedChests.bool());
    private final Setting enderChests = addBoolean("Render Enderchests", "Box ender chests", false);
    private final Setting enderChestColor = addColour("Ender Chest Color", "", Color.rgb(0x7E159C, 100 / 255.0f))
            .visibleWhen(() -> this.enderChests.bool());
    private final Setting hoppers = addBoolean("Render Hopper", "Box hoppers", false);
    private final Setting hopperColor = addColour("Hopper Color", "", Color.rgb(0x8A8A8A))
            .visibleWhen(() -> this.hoppers.bool());
    private final Setting furnaces = addBoolean("Render Furnace", "Box furnaces", false);
    private final Setting furnaceColor = addColour("Furnace Color", "", Color.rgb(0x5A5A5A))
            .visibleWhen(() -> this.furnaces.bool());
    private final Setting dispensers = addBoolean("Render Dispenser", "Box dispensers", false);
    private final Setting dispenserColor = addColour("Dispenser Color", "", Color.rgb(0x0114C8, 100 / 255.0f))
            .visibleWhen(() -> this.dispensers.bool());
    private final Setting droppers = addBoolean("Render Dropper", "Box droppers", false);
    private final Setting dropperColor = addColour("Dropper Color", "", Color.rgb(0x46C8C8, 100 / 255.0f))
            .visibleWhen(() -> this.droppers.bool());

    public StorageESP() {
        super("StorageESP", "Highlights chests and other containers", Category.VISUALS, Subcategory.MEDIUM);
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            Setting colour = colourFor(tile);
            if (colour == null) {
                continue;
            }
            int argb = colour.colour().argb();
            int x = tile.getPos().getX();
            int y = tile.getPos().getY();
            int z = tile.getPos().getZ();
            RenderUtils.iPds(x, y, z, 1.0, 1.0, 1.0, argb, true, true);
            if (outlineOpen.bool() && open(tile)) {
                RenderUtils.iPds(x, y, z, 1.0, 1.0, 1.0, argb & 0xFF000000 | ~argb & 0xFFFFFF, true, false);
            }
        }
    }

    /* Droppers are dispensers underneath, so the two only match their exact class. */
    private Setting colourFor(TileEntity tile) {
        if (tile instanceof TileEntityChest chest) {
            int type = chest.getChestType();
            if (type == 0) {
                return chests.bool() ? chestColor : null;
            }
            return type == 1 && trappedChests.bool() ? trappedChestColor : null;
        }
        if (tile instanceof TileEntityEnderChest) {
            return enderChests.bool() ? enderChestColor : null;
        }
        if (tile instanceof TileEntityHopper) {
            return hoppers.bool() ? hopperColor : null;
        }
        if (tile instanceof TileEntityFurnace) {
            return furnaces.bool() ? furnaceColor : null;
        }
        if (tile.getClass() == TileEntityDropper.class) {
            return droppers.bool() ? dropperColor : null;
        }
        if (tile.getClass() == TileEntityDispenser.class) {
            return dispensers.bool() ? dispenserColor : null;
        }
        return null;
    }

    private static boolean open(TileEntity tile) {
        if (tile instanceof TileEntityChest chest) {
            return chest.lidAngle > 0.0f;
        }
        return tile instanceof TileEntityEnderChest ender && ender.lidAngle > 0.0f;
    }
}
