package cobble.module.combat;

import cobble.event.Phase;
import cobble.event.PrePlayerInteractEvent;
import cobble.event.Subscribe;
import cobble.event.TickEndEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.combat.shared.BreakableBlockWhitelist;
import cobble.module.player.InventoryManager;
import cobble.util.ClientUtils;
import cobble.util.InputHookManager;
import cobble.util.block.BlockList;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.util.BlockPos;
import org.lwjgl.input.Mouse;

public class AutoClicker extends Module {
    public Setting cps;
    public Setting notUsingItem;
    public Setting breakBlocks;
    public Setting breakingBlocksWhitelist;
    public BlockList whitelistedBreakingBlocks;
    public Setting weaponOnly;
    public Setting disableInCreative;
    public Setting holdingLeft;
    public Setting whilstTargetting;
    public Setting whilstShiftingInInventory;
    public Setting startDelay;
    private long nextClickTime;
    private long mzN;
    private boolean TTiB;
    private boolean attackKeyHeld;
    private boolean attackKeyState;
    private GuiContainer guiContainer;
    private Slot slot;
    private Random rrL;

    public AutoClicker() {
        super("AutoClicker", "Best with delay remover", Category.COMBAT);
        this.cps = addNumber("CPS", "", 10.0F, 1.0F, 20.0F, 1);
        this.notUsingItem = addBoolean("Not using item", "", false);
        this.breakBlocks = addBoolean("Break blocks", "", false);
        this.breakingBlocksWhitelist = addBoolean("Breaking Blocks Whitelist", "", false);
        this.whitelistedBreakingBlocks = new BlockList();
        BreakableBlockWhitelist.TdpPo7(this.whitelistedBreakingBlocks);
        this.breakingBlocksWhitelist.setVisible(false);
        this.weaponOnly = addBoolean("Weapon only", "", false);
        this.disableInCreative = addBoolean("Disable in creative", "", false);
        this.holdingLeft = addBoolean("Holding Left", "", true);
        this.whilstTargetting = addBoolean("Whilst Targetting", "", false);
        this.whilstTargetting.setVisible(false);
        this.whilstShiftingInInventory = addBoolean("Whilst Shifting in Inventory", "", false);
        this.startDelay = addNumber("Start delay", "", 100.0F, 0.0F, 250.0F, 0);
    }

    @Override
    public String suffix() {
        double var1 = this.cps.number();
        return var1 == Math.rint(var1) ? Integer.toString((int)var1) : Double.toString(ClientUtils.WXYd(var1, 1));
    }

    @Override
    public void guiUpdate() {
        this.whilstTargetting.setVisible(ModuleManager.get(AimAssist.class) != null && ModuleManager.get(AimAssist.class).enabled());
        this.startDelay.setVisible(this.whilstShiftingInInventory.bool());
        this.breakingBlocksWhitelist.setVisible(this.breakBlocks.bool());
    }

    @Override
    public void onEnable(Context context) {
        this.rrL = new Random();
        this.nextClickTime = 0L;
        this.mzN = 0L;
        this.TTiB = false;
        this.clearInventoryTarget();
        this.TXebJt();
    }

    @Override
    public void onDisable(Context context) {
        this.mzN = 0L;
        this.clearInventoryTarget();
        this.TXebJt();
        if (mc != null && mc.gameSettings != null && mc.gameSettings.keyBindAttack != null) {
            this.releaseAttackKey(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            this.nextClickTime = 0L;
            this.TTiB = false;
        }
    }

    @Subscribe(priority = Subscribe.Priority.HIGHEST)
    public void onTickEnd(TickEndEvent var1) {
        if (!ClientUtils.isInWorld()) {
            this.mzN = 0L;
            this.clearInventoryTarget();
        } else if (var1.phase == Phase.START) {
            this.updateForcedAttackKey();
            this.processInventoryClick();
        } else if (var1.phase == Phase.END) {
            if (!this.isInventoryClickActive()) {
                this.mzN = 0L;
            }
        }
    }

    public void setInventoryTarget(GuiContainer var1, Slot var2) {
        this.guiContainer = var1;
        this.slot = var2;
    }

    private void processInventoryClick() {
        GuiContainer var1 = this.guiContainer;
        Slot var2 = this.slot;
        if (this.enabled() && var1 == mc.currentScreen && this.isInventoryClickActive()) {
            long var3 = System.currentTimeMillis();
            if (this.mzN == 0L) {
                this.mzN = var3 + (long)this.startDelay.number();
            }

            if (this.mzN <= var3) {
                if (var2 != null
                    && var2.slotNumber >= 0
                    && var2.slotNumber < var1.inventorySlots.inventorySlots.size()
                    && var1.inventorySlots.getSlot(var2.slotNumber) == var2
                    && var2.getHasStack()
                    && var2.canTakeStack(mc.thePlayer)) {
                    if (mc.playerController != null && mc.thePlayer != null) {
                        mc.playerController.windowClick(var1.inventorySlots.windowId, var2.slotNumber, 0, 1, mc.thePlayer);
                    }
                }
            }
        } else {
            this.mzN = 0L;
        }
    }

    private boolean isInventoryClickActive() {
        if (this.whilstShiftingInInventory.bool() && ClientUtils.isInWorld() && mc.currentScreen instanceof GuiContainer && this.isSupportedInventoryContainer((GuiContainer)mc.currentScreen)) {
            GuiContainer var1 = (GuiContainer)mc.currentScreen;
            return var1.inventorySlots == mc.thePlayer.openContainer
                && GuiScreen.isShiftKeyDown()
                && Mouse.isButtonDown(0)
                && !this.attackKeyHeld
                && mc.thePlayer.inventory.getItemStack() == null
                && (ModuleManager.get(InventoryManager.class) == null || !ModuleManager.get(InventoryManager.class).isExecutingPlan());
        } else {
            return false;
        }
    }

    private boolean isSupportedInventoryContainer(GuiContainer var1) {
        if (mc.thePlayer.openContainer instanceof ContainerPlayer && mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer) {
            return var1.inventorySlots == mc.thePlayer.inventoryContainer && var1.inventorySlots.windowId == 0;
        } else if (mc.thePlayer.openContainer instanceof ContainerChest && var1.inventorySlots == mc.thePlayer.openContainer) {
            IInventory var2 = ((ContainerChest)mc.thePlayer.openContainer).getLowerChestInventory();
            if (var2 != null && var2.getDisplayName() != null) {
                int var3 = var2.getSizeInventory();
                String var4 = var2.getDisplayName().getUnformattedText();
                if (var4 != null && var4.trim().isEmpty()) {
                    return var3 == 27 || var3 == 54;
                } else {
                    return var3 == 27
                        ? matchesContainerTitle(var4, "container.chest", "Chest") || matchesContainerTitle(var4, "container.enderchest", "Ender Chest")
                        : var3 == 54 && matchesContainerTitle(var4, "container.chestDouble", "Large Chest");
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private static boolean matchesContainerTitle(String var0, String var1, String var2) {
        if (var0 == null) {
            return false;
        } else {
            String var3 = var0.trim();
            return var3.equals(I18n.format(var1, new Object[0]).trim()) || var3.equals(var2);
        }
    }

    private void clearInventoryTarget() {
        this.guiContainer = null;
        this.slot = null;
    }

    @Subscribe
    public void onPrePlayerInteract(PrePlayerInteractEvent var1) {
        if (ClientUtils.isInWorld()) {
            int var2 = mc.gameSettings.keyBindAttack.getKeyCode();
            boolean var3 = Mouse.isButtonDown(0) && !this.attackKeyHeld;
            if (this.passesClickConditions()) {
                long var4 = System.currentTimeMillis();
                if (this.nextClickTime == 0L) {
                    this.nextClickTime = var4 + this.computeClickInterval();
                }

                int var6;
                for (var6 = 0; this.nextClickTime <= var4; this.nextClickTime = this.nextClickTime + this.computeClickInterval()) {
                    var6++;
                }

                if (this.breakBlocks.bool()) {
                    if (!var3) {
                        if (this.TTiB) {
                            KeyBinding.setKeyBindState(var2, false);
                            this.setAttackKeyState(false);
                            this.TTiB = false;
                        }
                    } else if (!mc.thePlayer.capabilities.allowEdit) {
                        if (this.TTiB) {
                            KeyBinding.setKeyBindState(var2, false);
                            this.setAttackKeyState(false);
                            this.TTiB = false;
                        }
                    } else if (mc.objectMouseOver != null) {
                        BlockPos var7 = mc.objectMouseOver.getBlockPos();
                        if (var7 != null) {
                            Block var8 = mc.theWorld.getBlockState(var7).getBlock();
                            if (this.isBlockBreakable(var7, var8)) {
                                if (!this.TTiB) {
                                    KeyBinding.setKeyBindState(var2, true);
                                    this.setAttackKeyState(true);
                                    this.TTiB = true;
                                }

                                return;
                            }

                            if (this.TTiB) {
                                KeyBinding.setKeyBindState(var2, false);
                                this.setAttackKeyState(false);
                                this.TTiB = false;
                                return;
                            }
                        } else {
                            this.TTiB = false;
                        }
                    }
                }

                if (!this.isAimAssistConditionMet()) {
                    this.releaseAttackKey(var2);
                    return;
                }

                for (int var9 = 0; var9 < var6; var9++) {
                    KeyBinding.onTick(var2);
                    this.setAttackKeyState(true);
                }
            } else {
                this.releaseAttackKey(var2);
            }
        }
    }

    private boolean passesClickConditions() {
        if (mc.currentScreen != null || !mc.inGameHasFocus) {
            return false;
        } else if (this.holdingLeft.bool() && !Mouse.isButtonDown(0)) {
            return false;
        } else if (this.notUsingItem.bool() && mc.thePlayer.isUsingItem()) {
            return false;
        } else {
            return this.disableInCreative.bool() && mc.thePlayer.capabilities.isCreativeMode ? false : !this.weaponOnly.bool() || ClientUtils.isHoldingWeapon() || this.isBlockBreakingWeapon();
        }
    }

    private boolean isBlockBreakingWeapon() {
        return this.breakBlocks.bool() && this.isBreakableBlockTargeted();
    }

    private boolean isAimAssistConditionMet() {
        return !this.whilstTargetting.bool()
            || ModuleManager.get(AimAssist.class) == null
            || !ModuleManager.get(AimAssist.class).enabled()
            || ModuleManager.get(AimAssist.class).hasTarget();
    }

    private void updateForcedAttackKey() {
        if (this.shouldForceAttackKey()) {
            InputHookManager.setMouseButtonState(0, true);
            this.attackKeyHeld = true;
        } else {
            this.TXebJt();
        }
    }

    private boolean shouldForceAttackKey() {
        return !this.holdingLeft.bool()
            && this.whilstTargetting.bool()
            && ModuleManager.get(AimAssist.class) != null
            && ModuleManager.get(AimAssist.class).hasTarget()
            && this.passesClickConditions()
            && !this.isBreakingWhitelistedBlock();
    }

    private boolean isBreakingWhitelistedBlock() {
        return this.breakBlocks.bool() && this.isBreakableBlockTargeted();
    }

    private boolean isBreakableBlockTargeted() {
        if (mc.objectMouseOver == null) {
            return false;
        } else {
            BlockPos var1 = mc.objectMouseOver.getBlockPos();
            if (var1 != null && mc.theWorld != null) {
                Block var2 = mc.theWorld.getBlockState(var1).getBlock();
                return this.isBlockBreakable(var1, var2);
            } else {
                return false;
            }
        }
    }

    private boolean isBlockBreakable(BlockPos var1, Block var2) {
        return var2 != Blocks.air && !(var2 instanceof BlockLiquid) && (!this.breakingBlocksWhitelist.bool() || BreakableBlockWhitelist.isBlockWhitelisted(mc, var1, this.whitelistedBreakingBlocks));
    }

    private void TXebJt() {
        if (this.attackKeyHeld) {
            InputHookManager.setMouseButtonState(0, false);
            this.attackKeyHeld = false;
        }
    }

    private void setAttackKeyState(boolean var1) {
        if (var1 || this.attackKeyState) {
            if (this.whilstTargetting.bool()) {
                InputHookManager.setMouseButtonState(0, var1);
            } else {
                InputHookManager.simulateMouseButton(0, var1);
            }

            this.attackKeyState = var1;
        }
    }

    private void releaseAttackKey(int var1) {
        this.TXebJt();
        this.nextClickTime = 0L;
        this.TTiB = false;
        KeyBinding.setKeyBindState(var1, false);
        this.setAttackKeyState(false);
    }

    private long computeClickInterval() {
        int var1 = Math.max(1, (int)this.cps.number());
        int var2 = 1000 / var1;
        int var3 = var2 + (this.rrL.nextInt(21) - 10);
        return Math.max(33, Math.min(180, var3));
    }

}
