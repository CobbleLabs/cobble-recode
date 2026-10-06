package cobble.legacy;

import cobble.core.Maths;
import cobble.core.Vec3;
import cobble.game.BlockKind;
import cobble.game.CameraView;
import cobble.game.CrosshairTarget;
import cobble.game.EntityView;
import cobble.game.Face;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.LocalPlayerView;
import cobble.hook.MenuScreen;
import cobble.util.InputHookManager;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityEnderCrystal;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The game under 1.8.9's MCP names.
 */
public abstract class LegacyGame implements Game {
    private static final int MAX_CAPTURED_ENTITIES = 320;
    private static final double MAX_CAPTURE_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int MAX_ENCHANTMENTS = 12;
    private static final int MAX_NAME_LENGTH = 96;
    private static final long STOP_TIMEOUT_SECONDS = 5;


    protected static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    protected static EntityPlayerSP player() {
        return Minecraft.getMinecraft().thePlayer;
    }

    protected static WorldClient world() {
        return Minecraft.getMinecraft().theWorld;
    }

    protected static EnumFacing facing(Face face) {
        return EnumFacing.values()[face.ordinal()];
    }

    @Override
    public float partialTicks() {
        return mc().timer.renderPartialTicks;
    }

    @Override
    public boolean paused() {
        return mc().isGamePaused();
    }

    @Override
    public boolean inWorld() {
        return world() != null;
    }

    @Override
    public void runOnClientThread(Runnable task) throws Exception {
        mc().addScheduledTask(task).get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public boolean multiplayerScreenOpen() {
        return mc().currentScreen instanceof GuiMultiplayer;
    }

    /**
     * Reads the game as of {@code partialTicks} through the current tick: the
     * frame's own fraction when drawing, 1 when acting from the tick, where the
     * tick position is the one the server holds.
     */
    @Override
    public FrameState capture(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        FrameState state = new FrameState();
        state.windowWidth = mc.displayWidth;
        state.windowHeight = mc.displayHeight;
        ScaledResolution scaled = new ScaledResolution(mc);
        state.scaledWidth = scaled.getScaledWidth();
        state.scaledHeight = scaled.getScaledHeight();
        state.scaleFactor = scaled.getScaleFactor();
        state.mouseSensitivity = mc.gameSettings.mouseSensitivity;
        state.fps = Minecraft.getDebugFPS();
        state.screenOpen = mc.currentScreen != null && !(mc.currentScreen instanceof MenuScreen);
        state.tickProgress = partialTicks;

        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            return state;
        }
        state.inWorld = true;
        state.worldTime = world.getTotalWorldTime();
        state.matricesValid = captureMatrices(mc, state);
        capturePlayer(mc, player, state);
        captureEntities(world, player, state);
        return state;
    }

    /*
     * ActiveRenderInfo holds the projection and modelview the last world pass
     * drew with, bob and hurt tilt folded in. That modelview is relative to the
     * interpolated feet of the view entity, which RenderManager keeps as the
     * render origin, so that is the camera position projection subtracts.
     */
    private static boolean captureMatrices(Minecraft mc, FrameState state) {
        read(ActiveRenderInfo.PROJECTION, state.projection.m);
        read(ActiveRenderInfo.MODELVIEW, state.view.m);
        RenderManager renderManager = mc.getRenderManager();
        state.camera.pos = new Vec3(renderManager.renderPosX, renderManager.renderPosY, renderManager.renderPosZ);
        Entity view = mc.getRenderViewEntity();
        if (view != null) {
            state.camera.yaw = view.rotationYaw;
            state.camera.pitch = view.rotationPitch;
        }
        // Both buffers are zero until the first world pass after joining.
        return state.projection.m[0] != 0.0f && state.projection.m[5] != 0.0f;
    }

    private static void read(FloatBuffer buffer, float[] into) {
        for (int i = 0; i < 16; ++i) {
            into[i] = buffer.get(i);
        }
    }

    private static void capturePlayer(Minecraft mc, EntityPlayerSP player, FrameState state) {
        LocalPlayerView view = state.player;
        view.valid = true;
        view.handle = player;
        view.pos = new Vec3(player.posX, player.posY, player.posZ);
        view.eyeHeight = player.getEyeHeight();
        view.eyePos = lerpedPos(player, state.tickProgress).add(0.0, view.eyeHeight, 0.0);
        view.velocity = new Vec3(player.motionX, player.motionY, player.motionZ);
        view.yaw = player.rotationYaw;
        view.pitch = player.rotationPitch;
        view.lastYaw = player.prevRotationYaw;
        view.lastPitch = player.prevRotationPitch;
        view.health = player.getHealth();
        view.maxHealth = player.getMaxHealth();
        view.armor = player.getTotalArmorValue();
        view.onGround = player.onGround;
        view.sneaking = player.isSneaking();
        view.sprinting = player.isSprinting();
        view.jumping = player.movementInput.jump;
        view.hurtTime = player.hurtTime;
        view.maxHurtTime = player.maxHurtTime;
        view.fallDistance = player.fallDistance;
        view.usingItem = player.isUsingItem();
        view.itemUseTicks = player.getItemInUseDuration();
        view.age = player.ticksExisted;
        state.ping = latency(player.getUniqueID());
    }

    private static void captureEntities(WorldClient world, EntityPlayerSP player, FrameState state) {
        Vec3 self = state.player.pos;
        for (Entity entity : world.loadedEntityList) {
            if (state.entities.size() >= MAX_CAPTURED_ENTITIES) {
                break;
            }
            if (!(entity instanceof EntityLivingBase living) || entity == player || !living.isEntityAlive()) {
                continue;
            }
            Vec3 tickPos = new Vec3(entity.posX, entity.posY, entity.posZ);
            if (tickPos.sub(self).lengthSquared() > MAX_CAPTURE_DISTANCE_SQUARED) {
                continue;
            }
            EntityView view = new EntityView();
            view.handle = living;
            view.id = entity.getEntityId();
            view.isPlayer = entity instanceof EntityPlayer;
            view.invisible = entity.isInvisible();
            view.sneaking = entity.isSneaking();
            view.onGround = entity.onGround;
            view.tickPos = tickPos;
            view.pos = lerpedPos(entity, state.tickProgress);
            view.width = entity.width;
            view.height = entity.height;
            view.eyePos = tickPos.add(0.0, entity.getEyeHeight(), 0.0);
            view.yaw = entity.rotationYaw;
            view.pitch = entity.rotationPitch;
            view.bodyYaw = living.renderYawOffset;
            view.health = living.getHealth();
            view.maxHealth = living.getMaxHealth();
            view.absorption = living.getAbsorptionAmount();
            view.armor = living.getTotalArmorValue();
            view.hurtTime = living.hurtTime;
            view.blocking = entity instanceof EntityPlayer other && other.isBlocking();
            view.distance = view.eyePos.sub(state.player.eyePos).length();
            state.entities.add(view);
        }
    }

    /** Where the renderer draws an entity: lastTickPos is what RenderManager interpolates from. */
    private static Vec3 lerpedPos(Entity entity, float partialTicks) {
        return new Vec3(Maths.lerp(entity.lastTickPosX, entity.posX, partialTicks),
                Maths.lerp(entity.lastTickPosY, entity.posY, partialTicks),
                Maths.lerp(entity.lastTickPosZ, entity.posZ, partialTicks));
    }

    @Override
    public void setRotation(float yaw, float pitch, boolean alsoPrevious) {
        EntityPlayerSP player = player();
        float clamped = Maths.clamp(pitch, -90.0f, 90.0f);
        player.rotationYaw = yaw;
        player.rotationPitch = clamped;
        /* The camera is built from lerp(partial, prevRotation, rotation).
         * Putting a rotation back without the previous one would still render
         * the rotation being taken back. */
        if (alsoPrevious) {
            player.prevRotationYaw = yaw;
            player.prevRotationPitch = clamped;
        }
    }

    @Override
    public void turn(float yawCounts, float pitchCounts) {
        player().setAngles(yawCounts, pitchCounts);
    }

    @Override
    public void attackEntity(Object target) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.attackEntity(mc.thePlayer, (Entity) target);
    }

    /** Parks vanilla left click, so a held button cannot double up on a module-driven swing. */
    @Override
    public void setAttackCooldown(int ticks) {
        Minecraft.getMinecraft().leftClickCounter = ticks;
    }

    /*
     * Both cooldowns count down inside the tick and the game only acts on the
     * tick they reach zero. Anything shortening one has to read it and lower
     * it, never write it every tick, or it never reaches zero at all.
     */
    @Override
    public int itemUseCooldown() {
        return Minecraft.getMinecraft().rightClickDelayTimer;
    }

    @Override
    public void setItemUseCooldown(int ticks) {
        Minecraft.getMinecraft().rightClickDelayTimer = ticks;
    }

    /** The delay the controller waits between finishing one block and starting the next. */
    @Override
    public int blockBreakingCooldown() {
        return Minecraft.getMinecraft().playerController.blockHitDelay;
    }

    @Override
    public void setBlockBreakingCooldown(int ticks) {
        Minecraft.getMinecraft().playerController.blockHitDelay = ticks;
    }

    @Override
    public boolean breakingBlock() {
        return Minecraft.getMinecraft().playerController.isHittingBlock;
    }

    @Override
    public void swingHand() {
        player().swingItem();
    }

    @Override
    public void setSprinting(boolean sprinting) {
        player().setSprinting(sprinting);
    }

    @Override
    public void jump() {
        player().jump();
    }

    @Override
    public boolean placeBlock(int x, int y, int z, Face face, Vec3 hit, boolean swing) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        boolean placed = mc.playerController.onPlayerRightClick(player, mc.theWorld,
                player.inventory.getCurrentItem(), new BlockPos(x, y, z), facing(face), toMinecraft(hit));
        if (placed && swing) {
            player.swingItem();
        }
        return placed;
    }

    /**
     * Makes a queued click act on a face the module chose. The tick hook runs
     * after the game's own crosshair pick and before the click is handled, so
     * the value written here is the one the click reads.
     */
    @Override
    public void setCrosshairTarget(int x, int y, int z, Face face, Vec3 hit) {
        Minecraft.getMinecraft().objectMouseOver =
                new MovingObjectPosition(toMinecraft(hit), facing(face), new BlockPos(x, y, z));
    }

    /** Whether placing against this position would replace it, as ItemBlock decides. */
    @Override
    public boolean isBlockReplaceable(int x, int y, int z) {
        WorldClient world = world();
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos);
    }

    @Override
    public boolean isBlockAir(int x, int y, int z) {
        return block(x, y, z) == Blocks.air;
    }

    @Override
    public boolean blockIs(int x, int y, int z, BlockKind kind) {
        Block block = block(x, y, z);
        return switch (kind) {
            case OBSIDIAN -> block == Blocks.obsidian;
            case BEDROCK -> block == Blocks.bedrock;
            case BED -> block == Blocks.bed;
            case RESPAWN_ANCHOR -> false;
        };
    }

    @Override
    public String blockId(int x, int y, int z) {
        Block block = block(x, y, z);
        ResourceLocation name = Block.blockRegistry.getNameForObject(block);
        return name != null ? name.getResourcePath() : block.getUnlocalizedName();
    }

    /** Vanilla's speed for the stack in a slot against a block, enchantments aside; a fist is 1. */
    @Override
    public float toolSpeed(int slot, int x, int y, int z) {
        ItemStack stack = player().inventory.getStackInSlot(slot);
        return stack != null ? stack.getStrVsBlock(block(x, y, z)) : 1.0f;
    }

    private static Block block(int x, int y, int z) {
        return world().getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    /** Vanilla's own "is there room" test; any entity that blocks placement blocks it here. */
    @Override
    public boolean boxEmpty(Vec3 minimum, Vec3 maximum) {
        return world().checkNoEntityCollision(new AxisAlignedBB(minimum.x(), minimum.y(), minimum.z(),
                maximum.x(), maximum.y(), maximum.z()), null);
    }

    protected static ItemStack heldItem() {
        return player().getHeldItem();
    }

    @Override
    public boolean handEmpty() {
        return heldItem() == null;
    }

    @Override
    public boolean holdingBlock() {
        ItemStack held = heldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    @Override
    public boolean holdingItem(ItemKind kind) {
        return stackIs(heldItem(), kind);
    }

    /** Inventory index of the first matching stack in [from, to], or -1. Hotbar is 0..8. */
    @Override
    public int findSlot(ItemKind kind, int from, int to) {
        EntityPlayerSP player = player();
        for (int slot = Math.max(0, from); slot <= Math.min(35, to); ++slot) {
            if (stackIs(player.inventory.getStackInSlot(slot), kind)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean stackIs(ItemStack stack, ItemKind kind) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        return switch (kind) {
            case SWORD -> item instanceof ItemSword;
            case AXE -> item instanceof ItemAxe;
            case OBSIDIAN -> item == Item.getItemFromBlock(Blocks.obsidian);
            case GLOWSTONE -> item == Item.getItemFromBlock(Blocks.glowstone);
            case ENDER_PEARL -> item instanceof ItemEnderPearl;
            case TOTEM, END_CRYSTAL, RESPAWN_ANCHOR, MACE, WIND_CHARGE -> false;
        };
    }

    @Override
    public int selectedSlot() {
        return player().inventory.currentItem;
    }

    /** Hotbar selection, sent to the server straight away the way the scroll wheel's is on the next tick. */
    @Override
    public void setSelectedSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.thePlayer.inventory.currentItem = slot;
        mc.playerController.syncCurrentPlayItem();
    }

    /**
     * Moves an inventory slot into a hotbar slot through the number-key click
     * the inventory screen sends. The player's container is open whether or
     * not a screen is showing, so this needs no window.
     */
    @Override
    public boolean swapSlot(int slot, int hotbar) {
        if (slot < 0 || slot > 35 || hotbar < 0 || hotbar > 8) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        // Container slots 36..44 are the hotbar; 9..35 match the inventory.
        int containerSlot = slot < 9 ? 36 + slot : slot;
        mc.playerController.windowClick(player.inventoryContainer.windowId, containerSlot, hotbar, 2, player);
        return true;
    }

    /* 1.7 and 1.8 have no off hand and no anchors. */
    @Override
    public boolean swapToOffhand(int slot) {
        return false;
    }

    @Override
    public int anchorCharges(int x, int y, int z) {
        return -1;
    }

    @Override
    public CrosshairTarget crosshair() {
        MovingObjectPosition hit = Minecraft.getMinecraft().objectMouseOver;
        if (hit == null) {
            return CrosshairTarget.MISS;
        }
        Vec3 point = hit.hitVec != null ? fromMinecraft(hit.hitVec) : Vec3.ZERO;
        return switch (hit.typeOfHit) {
            case BLOCK -> {
                BlockPos pos = hit.getBlockPos();
                yield new CrosshairTarget(CrosshairTarget.Kind.BLOCK, pos.getX(), pos.getY(), pos.getZ(),
                        Face.byIndex(hit.sideHit.ordinal()), point, null, -1, false, false);
            }
            case ENTITY -> new CrosshairTarget(CrosshairTarget.Kind.ENTITY, 0, 0, 0, Face.UP, point,
                    hit.entityHit, hit.entityHit.getEntityId(), hit.entityHit instanceof EntityEnderCrystal,
                    hit.entityHit instanceof EntitySlime);
            case MISS -> CrosshairTarget.MISS;
        };
    }

    @Override
    public boolean isZombie(Object entity) {
        return entity instanceof EntityZombie;
    }

    @Override
    public boolean isPlayer(Object entity) {
        return entity instanceof EntityPlayer;
    }

    @Override
    public int hurtResistance(Object entity) {
        return ((Entity) entity).hurtResistantTime;
    }

    /** Entity id of whatever the player last hit, or -1. */
    @Override
    public int attackTargetId() {
        EntityLivingBase target = player().getLastAttacker();
        return target != null ? target.getEntityId() : -1;
    }

    /** The first swing at a block, or the continuation of one. */
    @Override
    public void attackBlock(int x, int y, int z, Face face, boolean progressOnly) {
        Minecraft mc = Minecraft.getMinecraft();
        BlockPos pos = new BlockPos(x, y, z);
        if (progressOnly) {
            mc.playerController.onPlayerDamageBlock(pos, facing(face));
        } else {
            mc.playerController.clickBlock(pos, facing(face));
        }
    }

    /** Holds or releases the vanilla binding, which is what a CPS readout counts. */
    @Override
    public void pressKey(boolean useKey, boolean pressed) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        (useKey ? settings.keyBindUseItem : settings.keyBindAttack).pressed = pressed;
    }

    /**
     * Queues one click, which the game drains in the same tick with its own
     * clickMouse or rightClickMouse, so the interaction leaves from exactly
     * where a real click's would.
     */
    @Override
    public void queueClick(boolean useKey) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        ++(useKey ? settings.keyBindUseItem : settings.keyBindAttack).pressTime;
    }

    @Override
    public void queueMouseButton(int button, boolean pressed) {
        InputHookManager.queueMouseButton(button, pressed);
    }

    @Override
    public boolean inGameFocused() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.currentScreen == null && mc.inGameHasFocus;
    }

    @Override
    public int quickMoveSlot() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof GuiContainer gui) || gui.inventorySlots != mc.thePlayer.openContainer
                || mc.thePlayer.inventory.getItemStack() != null) {
            return -1;
        }
        net.minecraft.inventory.Slot slot = gui.theSlot;
        return slot != null && slot.getHasStack() && slot.canTakeStack(mc.thePlayer) ? slot.slotNumber : -1;
    }

    @Override
    public void quickMove(int containerSlot) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, containerSlot, 0, 1, mc.thePlayer);
    }

    @Override
    public void openInventory() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
    }

    @Override
    public boolean inventoryOpen() {
        return Minecraft.getMinecraft().currentScreen instanceof GuiInventory;
    }

    @Override
    public void closeScreen() {
        Minecraft.getMinecraft().displayGuiScreen(null);
    }

    @Override
    public void respawn() {
        player().respawnPlayer();
    }

    /** Sends a chat line, or a slash command when {@code command} is set. */
    @Override
    public void sendChat(String text, boolean command) {
        player().sendChatMessage(command && !text.startsWith("/") ? "/" + text : text);
    }

    /** Writes a line into the player's own chat without sending anything to the server. */
    @Override
    public void printChat(String text) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(text));
    }

    /** The line above the hotbar, where the server's own action bar messages go. */
    @Override
    public void overlayMessage(String text) {
        Minecraft.getMinecraft().ingameGUI.setRecordPlaying(text, false);
    }

    @Override
    public String entityName(Object entity) {
        return ((Entity) entity).getName();
    }

    @Override
    public String entityUuid(Object entity) {
        return ((Entity) entity).getUniqueID().toString();
    }

    /** Round trip the tab list has for this player, or -1. */
    @Override
    public int entityLatency(Object player) {
        return latency(((EntityPlayer) player).getUniqueID());
    }

    private static int latency(UUID id) {
        NetworkPlayerInfo info = Minecraft.getMinecraft().getNetHandler().getPlayerInfo(id);
        return info != null ? info.getResponseTime() : -1;
    }

    @Override
    public List<EntityRef> enderPearls() {
        List<EntityRef> pearls = new ArrayList<>();
        for (Entity entity : Minecraft.getMinecraft().theWorld.loadedEntityList) {
            if (entity instanceof EntityEnderPearl) {
                pearls.add(new EntityRef(entity, entity.getEntityId(), new Vec3(entity.posX, entity.posY, entity.posZ),
                        new Vec3(entity.motionX, entity.motionY, entity.motionZ)));
            }
        }
        return pearls;
    }

    @Override
    public List<EntityRef> endCrystals() {
        return List.of();
    }

    @Override
    public void setVelocity(Object entity, Vec3 velocity) {
        ((Entity) entity).setVelocity(velocity.x(), velocity.y(), velocity.z());
    }

    /** Plays a vanilla sound at the player, by sound event name such as "random.orb". */
    @Override
    public void playSound(String id, float volume, float pitch) {
        player().playSound(id.indexOf(':') >= 0 ? id : "minecraft:" + id, volume, pitch);
    }

    /** Address of the server, or an empty string in single player. */
    @Override
    public String serverAddress() {
        ServerData server = Minecraft.getMinecraft().getCurrentServerData();
        return server != null ? server.serverIP : "";
    }

    @Override
    public String gameDirectory() {
        return Minecraft.getMinecraft().mcDataDir.getAbsolutePath();
    }

    /*
     * Vanilla bindings by name. Writing one moves the player the way the
     * keyboard does, through the same state the game reads; the next physical
     * event on that key overwrites it, exactly as it would a real press.
     */
    private static KeyBinding binding(String name) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        return switch (name) {
            case "forward" -> settings.keyBindForward;
            case "back" -> settings.keyBindBack;
            case "left" -> settings.keyBindLeft;
            case "right" -> settings.keyBindRight;
            case "jump" -> settings.keyBindJump;
            case "sneak" -> settings.keyBindSneak;
            case "sprint" -> settings.keyBindSprint;
            case "attack" -> settings.keyBindAttack;
            case "use" -> settings.keyBindUseItem;
            default -> null;
        };
    }

    @Override
    public boolean setBinding(String name, boolean pressed) {
        KeyBinding binding = binding(name);
        if (binding == null) {
            return false;
        }
        binding.pressed = pressed;
        return true;
    }

    /**
     * Whether the game considers the binding held, which a module may have
     * changed; {@link #bindingPhysicallyHeld} is what the player is doing.
     */
    @Override
    public boolean bindingPressed(String name) {
        KeyBinding binding = binding(name);
        return binding != null && binding.pressed;
    }

    /** Whether the key or button the binding is bound to is down right now. */
    @Override
    public boolean bindingPhysicallyHeld(String name) {
        KeyBinding binding = binding(name);
        if (binding == null) {
            return false;
        }
        int code = binding.getKeyCode();
        if (code < 0) {
            return Mouse.isButtonDown(code + 100);
        }
        return code > 0 && Keyboard.isKeyDown(code);
    }


    /** One inventory slot, as a script sees it; 0..8 is the hotbar, 36..39 armour. */
    @Override
    public StackView stack(int slot) {
        ItemStack stack = player().inventory.getStackInSlot(slot);
        if (stack == null) {
            return StackView.EMPTY;
        }
        return new StackView(true, stack.getItem() instanceof ItemBlock, itemId(stack.getItem()),
                stack.stackSize, stack.getItemDamage(), stack.getMaxDamage());
    }

    protected static String itemId(Item item) {
        ResourceLocation name = Item.itemRegistry.getNameForObject(item);
        return name != null ? name.getResourcePath() : item.getUnlocalizedName();
    }


    @Override
    public List<EffectView> statusEffects(Object entity) {
        List<EffectView> effects = new ArrayList<>();
        for (PotionEffect effect : ((EntityLivingBase) entity).getActivePotionEffects()) {
            String name = potionName(effect.getPotionID());
            effects.add(new EffectView(name != null ? name : "potion_" + effect.getPotionID(),
                    effect.getAmplifier(), effect.getDuration()));
        }
        return effects;
    }

    /** The registry path later versions give a potion id, which scripts see on every version; null when unknown. */
    protected abstract String potionName(int id);

    protected abstract String enchantmentName(int id);

    @Override
    public EquipmentView equipment(Object handle, Slot slot) {
        EntityLivingBase entity = (EntityLivingBase) handle;
        // getCurrentArmor counts from the feet up.
        ItemStack stack = switch (slot) {
            case HEAD -> entity.getCurrentArmor(3);
            case CHEST -> entity.getCurrentArmor(2);
            case LEGS -> entity.getCurrentArmor(1);
            case FEET -> entity.getCurrentArmor(0);
            case MAIN_HAND -> entity.getHeldItem();
            case OFF_HAND -> null;
        };
        if (stack == null) {
            return EquipmentView.EMPTY;
        }
        String name = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        List<EnchantView> enchantments = new ArrayList<>();
        NBTTagList tags = stack.getEnchantmentTagList();
        for (int i = 0; tags != null && i < tags.tagCount() && enchantments.size() < MAX_ENCHANTMENTS; ++i) {
            NBTTagCompound tag = tags.getCompoundTagAt(i);
            int id = tag.getShort("id");
            String enchantment = enchantmentName(id);
            enchantments.add(new EnchantView(enchantment != null ? enchantment : "enchantment_" + id,
                    tag.getShort("lvl")));
        }
        return new EquipmentView(true, itemId(stack.getItem()), name, stack.stackSize, stack.getItemDamage(),
                stack.getMaxDamage(), enchantments);
    }


    @Override
    public SkinView playerSkin(Object player) {
        NetworkPlayerInfo info = ((AbstractClientPlayer) player).getPlayerInfo();
        if (info == null) {
            return SkinView.NONE;
        }
        ITextureObject texture = Minecraft.getMinecraft().getTextureManager().getTexture(info.getLocationSkin());
        return new SkinView(texture != null ? texture.getGlTextureId() : 0, "slim".equals(info.getSkinType()));
    }


    private static net.minecraft.util.Vec3 toMinecraft(Vec3 v) {
        return new net.minecraft.util.Vec3(v.x(), v.y(), v.z());
    }

    private static Vec3 fromMinecraft(net.minecraft.util.Vec3 v) {
        return new Vec3(v.xCoord, v.yCoord, v.zCoord);
    }
}
