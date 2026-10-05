package cobble.module.combat;

import cobble.core.Color;
import cobble.core.Time;
import cobble.event.PreUpdateEvent;
import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.module.shared.Facing;
import cobble.ui.Client;
import cobble.util.ClientUtils;
import cobble.util.render.RenderUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import org.lwjgl.input.Mouse;

/**
 * Hits whoever is around you without aiming at them. Anyone in the swing range
 * and inside the angle gets a swing, those in the attack range get the hit
 * too, at a rate rolled between the two speeds every attack. Holding block
 * lifts it for the tick of the hit and puts it back after.
 */
public final class KillAura extends LegacyModule {
    private static final int DISTANCE = 0;
    private static final int YAW = 1;
    private static final int ARMOR = 2;
    private static final int THREAT = 3;

    private final Setting players = addBoolean("Players", "Attack players", true);
    private final Setting mobs = addBoolean("Mobs", "Attack hostile mobs", false);
    private final Setting animals = addBoolean("Animals", "Attack animals", false);
    private final Setting invisibles = addBoolean("Invisibles", "Attack invisible entities", false);
    private final Setting teammates = addBoolean("Teammates", "Attack your own team", false);
    private final Setting minAps = addNumber("Min APS", "Fewest attacks per second", 6, 1, 20, 0);
    private final Setting maxAps = addNumber("Max APS", "Most attacks per second", 13, 1, 20, 0);
    private final Setting swingRange = addNumber("Swing range", "Swing at targets this close", 4.0f, 0.0f, 6.0f, 1);
    private final Setting attackRange = addNumber("Attack range", "Hit targets this close", 3.5f, 0.0f, 6.0f, 1);
    private final Setting maxAngle = addNumber("Max angle", "Only targets within this many degrees of your aim",
            90, 1, 360, 0);
    private final Setting maxTargets = addNumber("Max targets", "How many targets to attack at once", 1, 1, 6, 0);
    private final Setting targetMode = addChoice("Target Mode",
            "How targets are ranked; Armor and Threat fall back to distance for mobs",
            List.of("Distance", "Yaw", "Armor", "Threat", "Health"), DISTANCE);
    private final Setting disableOnDeath = addBoolean("Disable on death", "Turn off when you die", false);
    private final Setting requireMouseDown = addBoolean("Require mouse down", "Only while you hold left", false);
    private final Setting guiCheck = addBoolean("GUI check", "Don't attack while a screen is open", true);
    private final Setting weaponOnly = addBoolean("Weapon only", "Only while holding a sword or an axe", false);
    private final Setting showTarget = addBoolean("Show target", "Box the targets", false);
    private final Setting targetColor = addColour("Target Color", "Targets in swing range",
            Color.rgb(0xFFC870, 50 / 255.0f)).visibleWhen(() -> this.showTarget.bool());
    private final Setting attackColor = addColour("Attack Color", "Targets in attack range",
            Color.rgb(0xFF0000, 100 / 255.0f)).visibleWhen(() -> this.showTarget.bool());

    private final SplittableRandom random = new SplittableRandom();
    private final List<EntityLivingBase> targets = new ArrayList<>();
    private long lastAttack;
    private long attackDelay;
    private int pauseTicks;
    private boolean deathHandled;

    public KillAura() {
        super("KillAura", "Attacks entities around you without aiming at them", Category.COMBAT,
                Subcategory.ADVANCED);
    }

    @Override
    public String suffix() {
        return targetMode.choiceName();
    }

    @Override
    public void onEnable(Context context) {
        targets.clear();
        lastAttack = 0;
        attackDelay = 0;
    }

    @Override
    public void onDisable(Context context) {
        targets.clear();
    }

    public boolean hasTargets() {
        return enabled() && !targets.isEmpty();
    }

    @Subscribe(priority = Subscribe.Priority.LOW)
    public void onPreUpdate(PreUpdateEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (pauseTicks > 0) {
            --pauseTicks;
            return;
        }
        if (disableOnDeath.bool() && died(player)) {
            setEnabled(false, Client.context());
            return;
        }
        if (guiCheck.bool() && mc.currentScreen != null) {
            pauseTicks = 1;
            return;
        }
        if (requireMouseDown.bool() && !Mouse.isButtonDown(0)) {
            targets.clear();
            return;
        }
        updateTargets(player);
        long now = Time.nowMillis();
        if (now - lastAttack < attackDelay || targets.stream().noneMatch(target -> inSwing(player, target))) {
            return;
        }
        KeyBinding use = mc.gameSettings.keyBindUseItem;
        boolean blocking = Mouse.isButtonDown(1);
        if (blocking && use.isKeyDown()) {
            KeyBinding.setKeyBindState(use.getKeyCode(), false);
            return;
        }
        boolean attacked = false;
        for (EntityLivingBase target : targets) {
            if (!inSwing(player, target)) {
                continue;
            }
            player.swingItem();
            if (player.getDistanceToEntity(target) <= attackRange.number()) {
                mc.playerController.attackEntity(player, target);
            }
            if (blocking) {
                attacked = true;
                mc.rightClickMouse();
            }
        }
        if (!attacked && blocking) {
            KeyBinding.setKeyBindState(use.getKeyCode(), true);
        }
        lastAttack = now;
        int low = Math.min(minAps.intValue(), maxAps.intValue());
        int aps = low + random.nextInt(Math.max(minAps.intValue(), maxAps.intValue()) - low + 1);
        attackDelay = 1000L / aps;
    }

    @Subscribe(priority = Subscribe.Priority.LOW)
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!showTarget.bool()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        for (EntityLivingBase target : targets) {
            Setting colour = player.getDistanceToEntity(target) <= attackRange.number() ? attackColor : targetColor;
            RenderUtils.drawEntityBox(target, colour.colour().argb(), 1.5f, event.partialTicks);
        }
    }

    private void updateTargets(EntityPlayerSP player) {
        List<EntityLivingBase> found = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase living && attackable(player, living)) {
                found.add(living);
            }
        }
        Comparator<EntityLivingBase> byDistance = Comparator.comparingDouble(player::getDistanceToEntity);
        found.sort(switch (targetMode.choice()) {
            case YAW -> Comparator.comparingInt(target -> Facing.angleTo(player, target));
            case ARMOR -> playersFirstBy(KillAura::equipmentValue, byDistance);
            case THREAT -> playersFirstBy(KillAura::threat, byDistance);
            case DISTANCE -> byDistance;
            default -> Comparator.comparingDouble(EntityLivingBase::getHealth);
        });
        targets.clear();
        targets.addAll(found.subList(0, Math.min(maxTargets.intValue(), found.size())));
    }

    /* Two players compare by the score; anything else by distance. */
    private static Comparator<EntityLivingBase> playersFirstBy(ToDoubleFunction<EntityPlayer> score,
            Comparator<EntityLivingBase> byDistance) {
        return (first, second) -> first instanceof EntityPlayer one && second instanceof EntityPlayer two
                ? Double.compare(score.applyAsDouble(one), score.applyAsDouble(two))
                : byDistance.compare(first, second);
    }

    private boolean attackable(EntityPlayerSP player, EntityLivingBase target) {
        if (target == player || target.isDead || target.getHealth() <= 0.0f || target == player.ridingEntity
                || player.getDistanceToEntity(target) >= swingReach()
                || Facing.angleTo(player, target) > maxAngle.intValue() / 2) {
            return false;
        }
        if (weaponOnly.bool() && !holdingWeapon(player)) {
            return false;
        }
        if (target.isInvisible() && !invisibles.bool()) {
            return false;
        }
        if (target instanceof EntityPlayer other) {
            return players.bool() && !ClientUtils.isFriend(other) && !AntiBot.shouldHideEntity(other)
                    && (teammates.bool() || !ClientUtils.isTeammate(other));
        }
        return target instanceof IMob ? mobs.bool() : target instanceof EntityAnimal && animals.bool();
    }

    private boolean inSwing(EntityPlayerSP player, EntityLivingBase target) {
        return attackable(player, target) && player.getDistanceToEntity(target) <= swingReach();
    }

    /* The swing reaches at least a little past the attack. */
    private double swingReach() {
        return Math.max(swingRange.number(), attackRange.number() + 0.1);
    }

    private boolean died(EntityPlayerSP player) {
        if (player.isDead || player.getHealth() <= 0.0f) {
            return true;
        }
        boolean deathScreen = mc.currentScreen instanceof GuiGameOver;
        if (!deathScreen) {
            deathHandled = false;
            return false;
        }
        if (!deathHandled) {
            deathHandled = true;
            return true;
        }
        return false;
    }

    private static boolean holdingWeapon(EntityPlayerSP player) {
        ItemStack held = player.getHeldItem();
        return held != null && (held.getItem() instanceof ItemSword || held.getItem() instanceof ItemAxe);
    }

    /* The armor worn: its points and the protection on it. */
    private static double equipmentValue(EntityPlayer player) {
        double value = 0.0;
        for (ItemStack piece : player.inventory.armorInventory) {
            if (piece != null && piece.getItem() instanceof ItemArmor armor) {
                value += armor.damageReduceAmount
                        + EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, piece) * 0.75;
            }
        }
        return value;
    }

    /* How hard the held item hits, raised by Strength. */
    private static double threat(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        if (held == null) {
            return 0.0;
        }
        double damage = 1.0;
        if (held.getItem() instanceof ItemSword sword) {
            damage += sword.getDamageVsEntity();
        } else if (held.getItem() instanceof ItemTool tool) {
            damage += tool.getToolMaterial().getDamageVsEntity() + 1.0;
        }
        damage += EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, held) * 1.25;
        if (player.isPotionActive(Potion.damageBoost)) {
            damage *= 1.375 * (player.getActivePotionEffect(Potion.damageBoost).getAmplifier() + 1);
        }
        return damage;
    }
}
