package cobble.module.player;

import cobble.event.PreUpdateEvent;
import cobble.event.RenderWorldLastEvent;
import cobble.event.RotationEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.player.mlg.MlgState;
import cobble.module.player.mlg.MlgUtils;
import cobble.util.ClientUtils;
import cobble.util.raytrace.BlockNuker;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Items;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

public class MLG extends LegacyModule {
    private static final int ANY_FALL = 0;
    private static final int DAMAGE = 1;

    public Setting pickUp;
    public Setting swapSlot;
    private final Setting trigger;
    private final Setting minimumDamage;
    private final MlgState mlgState = new MlgState();

    public MLG() {
        super("MLG", "Places water under you to cancel fall damage", Category.PLAYER, Subcategory.MEDIUM);
        this.pickUp = addBoolean("Pick Up", "", true);
        this.swapSlot = addBoolean("Swap Slot", "", true);
        this.trigger = addChoice("Trigger", "Which falls are worth a bucket", List.of("Any fall", "Damage", "Lethal"),
                ANY_FALL);
        this.minimumDamage = addNumber("Minimum damage", "Least damage, in half hearts, a fall must do", 5, 1, 20, 0)
                .visibleWhen(() -> this.trigger.choice() == DAMAGE);
    }

    @Override
    public void onDisable(Context context) {
        this.restorePreviousSlot();
        this.mlgState.reset();
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent var1) {
        if (ClientUtils.isInWorld() && !mc.isGamePaused() && !mc.thePlayer.capabilities.isFlying && !mc.thePlayer.capabilities.isCreativeMode) {
            if (this.LvPq()) {
                MovingObjectPosition var2 = ClientUtils.rayTraceBlocksWithRotations(mc.playerController.getBlockReachDistance(), mc.thePlayer.rotationYaw, 90.0F);
                if (var2 != null && var2.typeOfHit == MovingObjectType.BLOCK && var2.sideHit == EnumFacing.UP) {
                    long var3 = System.currentTimeMillis();
                    if (this.mlgState.isReadyToRefill(var3)) {
                        if (!MlgUtils.EReKjl(mc.thePlayer.getHeldItem(), Items.water_bucket) && this.swapSlot.bool()) {
                            this.swapToWaterBucket();
                        }

                        if (MlgUtils.EReKjl(mc.thePlayer.getHeldItem(), Items.water_bucket)) {
                            this.mlgState.onWaterPlaced(var3, this.pickUp.bool());
                            this.sendItemUsePacket();
                            if (!this.pickUp.bool()) {
                                this.restorePreviousSlot();
                            }

                        }
                    }
                }
            }
        }
    }

    @Subscribe
    public void onPreUpdate(PreUpdateEvent var1) {
        if (!mc.isGamePaused()) {
            if (this.mlgState.shouldPickUpWater(System.currentTimeMillis()) && MlgUtils.EReKjl(mc.thePlayer.getHeldItem(), Items.bucket)) {
                this.mlgState.TcFaeva();
                this.sendItemUsePacket();
                this.restorePreviousSlot();
            }
        }
    }

    @Subscribe
    public void onRotation(RotationEvent var1) {
        if (!BlockNuker.steering()) {
            if ((this.LvPq() || this.mlgState.isRecentlyPlaced(System.currentTimeMillis())) && this.findWaterBucketSlot() != -1) {
                var1.setRotation(mc.thePlayer.rotationYaw, 90.0F, 45);
            }
        }
    }

    private void swapToWaterBucket() {
        int var1 = this.findWaterBucketSlot();
        if (var1 != -1) {
            this.mlgState.NOMpoo(mc.thePlayer.inventory.currentItem);
            ClientUtils.setHeldSlot(var1, true);
        }
    }

    private void restorePreviousSlot() {
        int var1 = this.mlgState.consumeSavedSlot();
        if (var1 != -1 && mc.thePlayer != null) {
            ClientUtils.setHeldSlot(var1, true);
        }
    }

    private int findWaterBucketSlot() {
        return MlgUtils.KMqC(mc.thePlayer.inventory, Items.water_bucket);
    }

    private void sendItemUsePacket() {
        mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
    }

    private boolean LvPq() {
        if (!MlgUtils.isFallingDangerously(mc.thePlayer)) {
            return false;
        }
        if (this.trigger.choice() == ANY_FALL) {
            return true;
        }
        float damage = this.landingDamage(mc.thePlayer);
        return this.trigger.choice() == DAMAGE ? damage >= this.minimumDamage.number()
                : damage >= mc.thePlayer.getHealth();
    }

    /* What the fall will do on landing, after Jump Boost, Resistance and Feather Falling; nothing into water. */
    private float landingDamage(EntityPlayerSP player) {
        BlockPos below = new BlockPos(player.posX, player.posY, player.posZ);
        while (below.getY() > 0) {
            below = below.down();
            Block block = mc.theWorld.getBlockState(below).getBlock();
            if (block.getMaterial().isLiquid()) {
                return 0.0f;
            }
            if (block.getMaterial().blocksMovement()) {
                break;
            }
        }
        float fall = player.fallDistance + (float) (player.posY - (below.getY() + 1));
        if (player.isPotionActive(Potion.jump)) {
            fall -= player.getActivePotionEffect(Potion.jump).getAmplifier() + 1;
        }
        float damage = Math.max(0, MathHelper.ceiling_float_int(fall - 3.0f));
        if (player.isPotionActive(Potion.resistance)) {
            damage *= (25 - (player.getActivePotionEffect(Potion.resistance).getAmplifier() + 1) * 5) / 25.0f;
        }
        int protection = Math.min(20,
                EnchantmentHelper.getEnchantmentModifierDamage(player.getInventory(), DamageSource.fall));
        return damage * (25 - protection) / 25.0f;
    }

}
