package cobble.v1_21.compat;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;

/**
 * The item classes 1.8.9 tested against, which 1.21.11 replaced with tags and
 * components: an item is a sword by its tag, armour by where it is worn, food
 * by its food component. The retarget rules send 1.8's instanceof tests here.
 */
public final class ItemTypes {
    private ItemTypes() {}

    private static boolean tagged(Object item, TagKey<Item> tag) {
        return item instanceof Item real && real.getRegistryEntry().isIn(tag);
    }

    public static boolean isSword(Object item) {
        return tagged(item, ItemTags.SWORDS);
    }

    public static boolean isPickaxe(Object item) {
        return tagged(item, ItemTags.PICKAXES);
    }

    /* 1.8's tools were the axe, the pickaxe and the shovel. */
    public static boolean isTool(Object item) {
        return tagged(item, ItemTags.AXES) || tagged(item, ItemTags.PICKAXES) || tagged(item, ItemTags.SHOVELS);
    }

    public static boolean isFood(Object item) {
        return item instanceof Item real && real.getComponents().contains(DataComponentTypes.FOOD);
    }

    public static boolean isArmor(Object item) {
        EquipmentSlot slot = armorSlot(item);
        return slot != null && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    static EquipmentSlot armorSlot(Object item) {
        if (!(item instanceof Item real)) {
            return null;
        }
        EquippableComponent equippable = real.getComponents().get(DataComponentTypes.EQUIPPABLE);
        return equippable != null ? equippable.slot() : null;
    }

    /** What the item adds to an attribute in its main slot, as its tooltip shows it. */
    static double modifier(Item item, RegistryEntry<EntityAttribute> attribute) {
        AttributeModifiersComponent modifiers = item.getComponents().getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        double total = 0.0;
        for (AttributeModifiersComponent.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().value();
            }
        }
        return total;
    }

    static double attackDamage(Item item) {
        return modifier(item, EntityAttributes.ATTACK_DAMAGE);
    }
}
