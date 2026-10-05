package cobble.v1_21.compat;

import net.minecraft.item.ItemStack;

/** 1.8.9's RenderItem; 1.21 draws items through its own GUI pass, which the overlay cannot reach, so none are drawn. */
public final class RenderItem {
    static final RenderItem INSTANCE = new RenderItem();

    public float zLevel;

    private RenderItem() {}

    public void renderItemIntoGUI(ItemStack stack, int x, int y) {}

    public void renderItemAndEffectIntoGUI(ItemStack stack, int x, int y) {}
}
