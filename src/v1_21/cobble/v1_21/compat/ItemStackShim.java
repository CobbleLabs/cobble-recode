package cobble.v1_21.compat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ThrowablePotionItem;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;

/**
 * 1.8.9's ItemStack on 1.21.11's. Metadata, tags and display names are item
 * components on 1.21; a subtype that was metadata on 1.8 is an item of its
 * own, so the metadata left is the damage.
 */
public final class ItemStackShim {
    private ItemStackShim() {}

    public static int stackSize(ItemStack stack) {
        return stack.getCount();
    }

    public static void stackSize(ItemStack stack, int count) {
        stack.setCount(count);
    }

    public static ItemStack create(Block block) {
        return new ItemStack(block);
    }

    public static ItemStack create(Item item) {
        return new ItemStack(item);
    }

    public static ItemStack create(Item item, int count, int metadata) {
        return new ItemStack(item, count);
    }

    /** The stack's modifiers keyed by attribute id, as 1.8 keyed them by name. */
    public static Multimap<String, EntityAttributeModifier> getAttributeModifiers(ItemStack stack) {
        Multimap<String, EntityAttributeModifier> modifiers = HashMultimap.create();
        for (AttributeModifiersComponent.Entry entry : stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                AttributeModifiersComponent.DEFAULT).modifiers()) {
            modifiers.put(entry.attribute().getIdAsString(), entry.modifier());
        }
        return modifiers;
    }

    /* 1.21 stacks keep components, not a tag; what is left of 1.8's tag is the custom data. */
    public static NbtCompound getTagCompound(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null ? data.copyNbt() : null;
    }

    public static void setTagCompound(ItemStack stack, NbtCompound tag) {
        if (tag == null) {
            stack.remove(DataComponentTypes.CUSTOM_DATA);
        } else {
            stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        }
    }

    public static boolean areItemStackTagsEqual(ItemStack a, ItemStack b) {
        return ItemStacks.orEmpty(a).getComponentChanges().equals(ItemStacks.orEmpty(b).getComponentChanges());
    }

    public static boolean canHarvestBlock(ItemStack stack, Block block) {
        return stack.isSuitableFor(block.getDefaultState());
    }

    public static String getDisplayName(ItemStack stack) {
        return stack.getName().getString();
    }

    public static boolean getHasSubtypes(ItemStack stack) {
        return false;
    }

    public static int getItemDamage(ItemStack stack) {
        return stack.getDamage();
    }

    /* A thrown potion carried 1.8's splash bit in its metadata, which ItemPotion.isSplash tests. */
    public static int getMetadata(ItemStack stack) {
        int metadata = stack.isDamageable() ? stack.getDamage() : 0;
        return stack.getItem() instanceof ThrowablePotionItem ? metadata | ItemPotionShim.SPLASH : metadata;
    }

    public static int getMaxItemUseDuration(ItemStack stack) {
        return stack.getMaxUseTime(MinecraftClient.getInstance().player);
    }

    public static int getMaxStackSize(ItemStack stack) {
        return stack.getMaxCount();
    }

    public static float getStrVsBlock(ItemStack stack, Block block) {
        return stack.getMiningSpeedMultiplier(block.getDefaultState());
    }

    public static List<String> getTooltip(ItemStack stack, PlayerEntity player, boolean advanced) {
        List<String> lines = new ArrayList<>();
        for (Text line : stack.getTooltip(Item.TooltipContext.DEFAULT, player,
                advanced ? TooltipType.ADVANCED : TooltipType.BASIC)) {
            lines.add(line.getString());
        }
        return lines;
    }

    public static String getUnlocalizedName(ItemStack stack) {
        return stack.getItem().getTranslationKey();
    }

    public static boolean hasDisplayName(ItemStack stack) {
        return stack.getCustomName() != null;
    }

    public static boolean hasTagCompound(ItemStack stack) {
        return !stack.getComponentChanges().isEmpty();
    }

    public static boolean isItemEqual(ItemStack stack, ItemStack other) {
        return ItemStack.areItemsEqual(stack, ItemStacks.orEmpty(other));
    }

    public static boolean isItemStackDamageable(ItemStack stack) {
        return stack.isDamageable();
    }

    public static ItemStack setStackDisplayName(ItemStack stack, String name) {
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    /* One tag for each enchantment, empty as only the count is read; null without any, as on 1.8. */
    public static NbtList getEnchantmentTagList(ItemStack stack) {
        if (stack.getEnchantments().isEmpty()) {
            return null;
        }
        NbtList list = new NbtList();
        for (int i = 0; i < stack.getEnchantments().getSize(); ++i) {
            list.add(new NbtCompound());
        }
        return list;
    }
}
