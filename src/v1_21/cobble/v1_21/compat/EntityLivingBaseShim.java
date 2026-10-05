package cobble.v1_21.compat;

import java.util.Collection;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.Vec3d;

/** 1.8.9's EntityLivingBase on 1.21.11's LivingEntity; a potion is a status effect's registry entry here. */
public final class EntityLivingBaseShim {
    /* 1.8's armour slots count up from the boots. */
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private EntityLivingBaseShim() {}

    public static boolean isSwingInProgress(LivingEntity entity) {
        return entity.handSwinging;
    }

    public static Collection<StatusEffectInstance> getActivePotionEffects(LivingEntity entity) {
        return entity.getStatusEffects();
    }

    /*
     * The limb and swing animation 1.8 kept in fields feed only the model of
     * a posed copy, which the overlay cannot draw on 1.21; they read as still.
     */
    public static float limbSwing(LivingEntity entity) {
        return 0.0f;
    }

    public static void limbSwing(LivingEntity entity, float value) {}

    public static float limbSwingAmount(LivingEntity entity) {
        return 0.0f;
    }

    public static void limbSwingAmount(LivingEntity entity, float value) {}

    public static float prevLimbSwingAmount(LivingEntity entity) {
        return 0.0f;
    }

    public static void prevLimbSwingAmount(LivingEntity entity, float value) {}

    public static void swingProgress(LivingEntity entity, float value) {
        entity.handSwingProgress = value;
    }

    public static void prevSwingProgress(LivingEntity entity, float value) {
        entity.lastHandSwingProgress = value;
    }

    public static void isSwingInProgress(LivingEntity entity, boolean swinging) {
        entity.handSwinging = swinging;
    }

    public static void swingProgressInt(LivingEntity entity, int ticks) {
        entity.handSwingTicks = ticks;
    }

    public static int jumpTicks(LivingEntity entity) {
        return entity.jumpingCooldown;
    }

    public static void jumpTicks(LivingEntity entity, int ticks) {
        entity.jumpingCooldown = ticks;
    }

    public static float moveForward(LivingEntity entity) {
        return entity.forwardSpeed;
    }

    public static void moveForward(LivingEntity entity, float value) {
        entity.forwardSpeed = value;
    }

    public static float moveStrafing(LivingEntity entity) {
        return entity.sidewaysSpeed;
    }

    public static void moveStrafing(LivingEntity entity, float value) {
        entity.sidewaysSpeed = value;
    }

    public static float renderYawOffset(LivingEntity entity) {
        return entity.bodyYaw;
    }

    public static void renderYawOffset(LivingEntity entity, float value) {
        entity.bodyYaw = value;
    }

    public static float prevRenderYawOffset(LivingEntity entity) {
        return entity.lastBodyYaw;
    }

    public static void prevRenderYawOffset(LivingEntity entity, float value) {
        entity.lastBodyYaw = value;
    }

    public static float rotationYawHead(LivingEntity entity) {
        return entity.headYaw;
    }

    public static void rotationYawHead(LivingEntity entity, float value) {
        entity.headYaw = value;
    }

    public static float prevRotationYawHead(LivingEntity entity) {
        return entity.lastHeadYaw;
    }

    public static void prevRotationYawHead(LivingEntity entity, float value) {
        entity.lastHeadYaw = value;
    }

    public static float swingProgress(LivingEntity entity) {
        return entity.handSwingProgress;
    }

    public static float prevSwingProgress(LivingEntity entity) {
        return entity.lastHandSwingProgress;
    }

    public static int swingProgressInt(LivingEntity entity) {
        return entity.handSwingTicks;
    }

    public static void addPotionEffect(LivingEntity entity, StatusEffectInstance effect) {
        entity.addStatusEffect(effect);
    }

    public static boolean canEntityBeSeen(LivingEntity entity, net.minecraft.entity.Entity other) {
        return entity.canSee(other);
    }

    public static StatusEffectInstance getActivePotionEffect(LivingEntity entity, RegistryEntry<StatusEffect> potion) {
        return entity.getStatusEffect(potion);
    }

    public static boolean isPotionActive(LivingEntity entity, RegistryEntry<StatusEffect> potion) {
        return entity.hasStatusEffect(potion);
    }

    public static void removePotionEffectClient(LivingEntity entity, int id) {
        RegistryEntry<StatusEffect> potion = PotionShim.byId(id);
        if (potion != null) {
            entity.removeStatusEffectInternal(potion);
        }
    }

    public static ItemStack getCurrentArmor(LivingEntity entity, int slot) {
        return ItemStacks.orNull(entity.getEquippedStack(ARMOR[slot]));
    }

    public static ItemStack getHeldItem(LivingEntity entity) {
        return ItemStacks.orNull(entity.getMainHandStack());
    }

    public static float getJumpUpwardsMotion(LivingEntity entity) {
        return (float) entity.getAttributeValue(EntityAttributes.JUMP_STRENGTH);
    }

    public static Vec3d getLook(LivingEntity entity, float partialTicks) {
        return entity.getRotationVec(partialTicks);
    }

    public static int getTotalArmorValue(LivingEntity entity) {
        return entity.getArmor();
    }

    public static boolean isEntityAlive(LivingEntity entity) {
        return entity.isAlive();
    }

    public static boolean isOnLadder(LivingEntity entity) {
        return entity.isClimbing();
    }

    public static CreatureAttribute getCreatureAttribute(LivingEntity entity) {
        return CreatureAttribute.of(entity);
    }

    public static EntityAttributeInstance getEntityAttribute(LivingEntity entity,
                                                             RegistryEntry<EntityAttribute> attribute) {
        return entity.getAttributeInstance(attribute);
    }

    public static boolean isOnSameTeam(LivingEntity entity, LivingEntity other) {
        return entity.isTeammate(other);
    }
}
