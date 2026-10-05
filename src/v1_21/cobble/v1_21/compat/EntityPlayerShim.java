package cobble.v1_21.compat;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;

/** 1.8.9's EntityPlayer on 1.21.11's PlayerEntity. */
public final class EntityPlayerShim {
    /* 1.8's equipment slots: the held item, then the armour from the boots up. */
    private static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.MAINHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private EntityPlayerShim() {}

    public static PlayerAbilities capabilities(PlayerEntity player) {
        return player.getAbilities();
    }

    public static FishingBobberEntity fishEntity(PlayerEntity player) {
        return player.fishHook;
    }

    public static PlayerInventory inventory(PlayerEntity player) {
        return player.getInventory();
    }

    public static ScreenHandler inventoryContainer(PlayerEntity player) {
        return player.playerScreenHandler;
    }

    public static ScreenHandler openContainer(PlayerEntity player) {
        return player.currentScreenHandler;
    }

    public static void openContainer(PlayerEntity player, ScreenHandler handler) {
        player.currentScreenHandler = handler;
    }

    public static int itemInUseCount(PlayerEntity player) {
        return player.getItemUseTimeLeft();
    }

    public static void clearItemInUse(PlayerEntity player) {
        player.stopUsingItem();
    }

    public static ItemStack getEquipmentInSlot(PlayerEntity player, int slot) {
        return ItemStacks.orNull(player.getEquippedStack(EQUIPMENT[slot]));
    }

    public static float getEyeHeight(PlayerEntity player) {
        return player.getStandingEyeHeight();
    }

    public static HungerManager getFoodStats(PlayerEntity player) {
        return player.getHungerManager();
    }

    public static ItemStack getHeldItem(PlayerEntity player) {
        return ItemStacks.orNull(player.getMainHandStack());
    }

    /** 1.8 names the armour this, boots first. */
    public static ItemStack[] getInventory(PlayerEntity player) {
        ItemStack[] armor = new ItemStack[4];
        for (int slot = 0; slot < armor.length; ++slot) {
            armor[slot] = ItemStacks.orNull(player.getEquippedStack(EQUIPMENT[slot + 1]));
        }
        return armor;
    }

    public static ItemStack getItemInUse(PlayerEntity player) {
        return player.isUsingItem() ? ItemStacks.orNull(player.getActiveItem()) : null;
    }

    public static int getItemInUseCount(PlayerEntity player) {
        return player.getItemUseTimeLeft();
    }

    public static int getItemInUseDuration(PlayerEntity player) {
        return player.getItemUseTime();
    }

    public static String getName(PlayerEntity player) {
        return player.getName().getString();
    }

    public static Team getTeam(PlayerEntity player) {
        return player.getScoreboardTeam();
    }

    public static int getTotalArmorValue(PlayerEntity player) {
        return player.getArmor();
    }

    public static Scoreboard getWorldScoreboard(PlayerEntity player) {
        return player.getEntityWorld().getScoreboard();
    }

    public static boolean isPlayerSleeping(PlayerEntity player) {
        return player.isSleeping();
    }

    public static void setItemInUse(PlayerEntity player, ItemStack stack, int duration) {
        player.setCurrentHand(Hand.MAIN_HAND);
    }
}
