package cobble.module.player;

import cobble.event.PacketSendEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import org.lwjgl.input.Mouse;

/**
 * Cobble's NoItemRelease: once an item of the chosen kind has been used, the
 * packet that releases it is held back, so the server keeps the player
 * blocking or eating after the button is let go. Using anything else ends it.
 */
public final class NoItemRelease extends LegacyModule {
    private final Setting mode = addChoice("Mode", "Which items keep being used",
            List.of("Consumable", "Sword", "All"), 2);

    private boolean using;

    public NoItemRelease() {
        super("NoItemRelease", "Keeps using the item after you let go", Category.PLAYER, Subcategory.MEDIUM);
    }

    @Override
    public String suffix() {
        return mode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        using = false;
    }

    @Override
    public void onTick(Context context) {
        if (mc.thePlayer == null || mc.currentScreen != null || !Mouse.isButtonDown(1)) {
            return;
        }
        /* Held state only changes on a press: letting go is what this keeps from counting. */
        using = keepsUsing(mc.thePlayer.getHeldItem());
    }

    @Subscribe
    public void onPacketSend(PacketSendEvent event) {
        if (using && event.getPacket() instanceof C07PacketPlayerDigging digging
                && digging.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
            event.setCancelled(true);
        }
    }

    /* A bow or a rod does its work on the release, so those always let go. */
    private boolean keepsUsing(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof ItemBow || item instanceof ItemFishingRod) {
            return false;
        }
        return switch (mode.choiceName()) {
            case "Consumable" -> item instanceof ItemFood || item instanceof ItemPotion || item instanceof ItemBlock;
            case "Sword" -> item instanceof ItemSword;
            default -> true;
        };
    }
}
