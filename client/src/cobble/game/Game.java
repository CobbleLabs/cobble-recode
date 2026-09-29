package cobble.game;

import cobble.core.Maths;
import cobble.core.Vec3;
import cobble.hook.MenuScreen;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
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
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
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
 * Everything the client reads from and does to the game. Reads are gathered
 * into a {@link FrameState}; writes go straight through. Every method runs on
 * the client thread, from a hook, with the game's own state consistent.
 */
public final class Game {
    private static final int MAX_CAPTURED_ENTITIES = 320;
    private static final double MAX_CAPTURE_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int MAX_ENCHANTMENTS = 12;
    private static final int MAX_NAME_LENGTH = 96;

    private static Map<Integer, String> potionNames;
    private static Map<Integer, String> enchantmentNames;

    private Game() {}

    public static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    public static EntityPlayerSP player() {
        return Minecraft.getMinecraft().thePlayer;
    }

    public static WorldClient world() {
        return Minecraft.getMinecraft().theWorld;
    }

    /**
     * Reads the game as of {@code partialTicks} through the current tick: the
     * frame's own fraction when drawing, 1 when acting from the tick, where the
     * tick position is the one the server holds.
     */
    public static FrameState capture(float partialTicks) {
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

    public static void setRotation(float yaw, float pitch, boolean alsoPrevious) {
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

    public static void attackEntity(Entity target) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.attackEntity(mc.thePlayer, target);
    }

    /** Parks vanilla left click, so a held button cannot double up on a module-driven swing. */
    public static void setAttackCooldown(int ticks) {
        Minecraft.getMinecraft().leftClickCounter = ticks;
    }

    /*
     * Both cooldowns count down inside the tick and the game only acts on the
     * tick they reach zero. Anything shortening one has to read it and lower
     * it, never write it every tick, or it never reaches zero at all.
     */
    public static int itemUseCooldown() {
        return Minecraft.getMinecraft().rightClickDelayTimer;
    }

    public static void setItemUseCooldown(int ticks) {
        Minecraft.getMinecraft().rightClickDelayTimer = ticks;
    }

    /** The delay the controller waits between finishing one block and starting the next. */
    public static int blockBreakingCooldown() {
        return Minecraft.getMinecraft().playerController.blockHitDelay;
    }

    public static void setBlockBreakingCooldown(int ticks) {
        Minecraft.getMinecraft().playerController.blockHitDelay = ticks;
    }

    public static boolean breakingBlock() {
        return Minecraft.getMinecraft().playerController.isHittingBlock;
    }

    public static void swingHand() {
        player().swingItem();
    }

    public static void setSprinting(boolean sprinting) {
        player().setSprinting(sprinting);
    }

    public static void jump() {
        player().jump();
    }

    public static boolean placeBlock(int x, int y, int z, EnumFacing face, Vec3 hit, boolean swing) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        boolean placed = mc.playerController.onPlayerRightClick(player, mc.theWorld,
                player.inventory.getCurrentItem(), new BlockPos(x, y, z), face, toMinecraft(hit));
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
    public static void setCrosshairTarget(int x, int y, int z, EnumFacing face, Vec3 hit) {
        Minecraft.getMinecraft().objectMouseOver =
                new MovingObjectPosition(toMinecraft(hit), face, new BlockPos(x, y, z));
    }

    /** Whether placing against this position would replace it, as ItemBlock decides. */
    public static boolean isBlockReplaceable(int x, int y, int z) {
        WorldClient world = world();
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos);
    }

    public static boolean isBlockAir(int x, int y, int z) {
        return block(x, y, z) == Blocks.air;
    }

    public static boolean blockIs(int x, int y, int z, BlockKind kind) {
        Block block = block(x, y, z);
        return switch (kind) {
            case OBSIDIAN -> block == Blocks.obsidian;
            case BEDROCK -> block == Blocks.bedrock;
            case BED -> block == Blocks.bed;
            case RESPAWN_ANCHOR -> false;
        };
    }

    public static String blockId(int x, int y, int z) {
        Block block = block(x, y, z);
        ResourceLocation name = Block.blockRegistry.getNameForObject(block);
        return name != null ? name.getResourcePath() : block.getUnlocalizedName();
    }

    /** Vanilla's speed for the stack in a slot against a block, enchantments aside; a fist is 1. */
    public static float toolSpeed(int slot, int x, int y, int z) {
        ItemStack stack = player().inventory.getStackInSlot(slot);
        return stack != null ? stack.getStrVsBlock(block(x, y, z)) : 1.0f;
    }

    private static Block block(int x, int y, int z) {
        return world().getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    /** Vanilla's own "is there room" test; any entity that blocks placement blocks it here. */
    public static boolean boxEmpty(Vec3 minimum, Vec3 maximum) {
        return world().checkNoEntityCollision(new AxisAlignedBB(minimum.x(), minimum.y(), minimum.z(),
                maximum.x(), maximum.y(), maximum.z()), null);
    }

    public static ItemStack heldItem() {
        return player().getHeldItem();
    }

    public static boolean handEmpty() {
        return heldItem() == null;
    }

    public static boolean holdingBlock() {
        ItemStack held = heldItem();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    public static boolean holdingSword() {
        return holdingItem(ItemKind.SWORD);
    }

    /** Sword or axe: what AutoTool treats as a weapon worth keeping in hand. */
    public static boolean holdingWeapon() {
        return holdingItem(ItemKind.SWORD) || holdingItem(ItemKind.AXE);
    }

    public static boolean holdingItem(ItemKind kind) {
        return stackIs(heldItem(), kind);
    }

    /** Inventory index of the first matching stack in [from, to], or -1. Hotbar is 0..8. */
    public static int findSlot(ItemKind kind, int from, int to) {
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
            case GLOWSTONE -> item == Items.glowstone_dust || item == Item.getItemFromBlock(Blocks.glowstone);
            case TOTEM, END_CRYSTAL, RESPAWN_ANCHOR, MACE -> false;
        };
    }

    public static int selectedSlot() {
        return player().inventory.currentItem;
    }

    /** Hotbar selection, sent to the server straight away the way the scroll wheel's is on the next tick. */
    public static void setSelectedSlot(int slot) {
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
    public static boolean swapSlot(int slot, int hotbar) {
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

    public static CrosshairTarget crosshair() {
        MovingObjectPosition hit = Minecraft.getMinecraft().objectMouseOver;
        if (hit == null) {
            return CrosshairTarget.MISS;
        }
        Vec3 point = hit.hitVec != null ? fromMinecraft(hit.hitVec) : Vec3.ZERO;
        return switch (hit.typeOfHit) {
            case BLOCK -> {
                BlockPos pos = hit.getBlockPos();
                yield new CrosshairTarget(CrosshairTarget.Kind.BLOCK, pos.getX(), pos.getY(), pos.getZ(),
                        hit.sideHit, point, null, false, false);
            }
            case ENTITY -> new CrosshairTarget(CrosshairTarget.Kind.ENTITY, 0, 0, 0, EnumFacing.UP, point,
                    hit.entityHit, hit.entityHit instanceof EntityEnderCrystal,
                    hit.entityHit instanceof EntitySlime);
            case MISS -> CrosshairTarget.MISS;
        };
    }

    public static boolean isZombie(Entity entity) {
        return entity instanceof EntityZombie;
    }

    /** Entity id of whatever the player last hit, or -1. */
    public static int attackTargetId() {
        EntityLivingBase target = player().getLastAttacker();
        return target != null ? target.getEntityId() : -1;
    }

    /** The first swing at a block, or the continuation of one. */
    public static void attackBlock(int x, int y, int z, EnumFacing face, boolean progressOnly) {
        Minecraft mc = Minecraft.getMinecraft();
        BlockPos pos = new BlockPos(x, y, z);
        if (progressOnly) {
            mc.playerController.onPlayerDamageBlock(pos, face);
        } else {
            mc.playerController.clickBlock(pos, face);
        }
    }

    /** Holds or releases the vanilla binding, which is what a CPS readout counts. */
    public static void pressKey(boolean useKey, boolean pressed) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        (useKey ? settings.keyBindUseItem : settings.keyBindAttack).pressed = pressed;
    }

    /**
     * Queues one click, which the game drains in the same tick with its own
     * clickMouse or rightClickMouse, so the interaction leaves from exactly
     * where a real click's would.
     */
    public static void queueClick(boolean useKey) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        ++(useKey ? settings.keyBindUseItem : settings.keyBindAttack).pressTime;
    }

    public static void openInventory() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
    }

    public static boolean inventoryOpen() {
        return Minecraft.getMinecraft().currentScreen instanceof GuiInventory;
    }

    public static void closeScreen() {
        Minecraft.getMinecraft().displayGuiScreen(null);
    }

    public static void respawn() {
        player().respawnPlayer();
    }

    /** Sends a chat line, or a slash command when {@code command} is set. */
    public static void sendChat(String text, boolean command) {
        player().sendChatMessage(command && !text.startsWith("/") ? "/" + text : text);
    }

    /** Writes a line into the player's own chat without sending anything to the server. */
    public static void printChat(String text) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(text));
    }

    /** The line above the hotbar, where the server's own action bar messages go. */
    public static void overlayMessage(String text) {
        Minecraft.getMinecraft().ingameGUI.setRecordPlaying(text, false);
    }

    public static String entityName(Entity entity) {
        return entity.getName();
    }

    public static String entityUuid(Entity entity) {
        return entity.getUniqueID().toString();
    }

    /** Round trip the tab list has for this player, or -1. */
    public static int entityLatency(EntityPlayer player) {
        return latency(player.getUniqueID());
    }

    private static int latency(UUID id) {
        NetworkPlayerInfo info = Minecraft.getMinecraft().getNetHandler().getPlayerInfo(id);
        return info != null ? info.getResponseTime() : -1;
    }

    public static void setVelocity(Entity entity, Vec3 velocity) {
        entity.setVelocity(velocity.x(), velocity.y(), velocity.z());
    }

    /** Plays a vanilla sound at the player, by sound event name such as "random.orb". */
    public static void playSound(String id, float volume, float pitch) {
        player().playSound(id.indexOf(':') >= 0 ? id : "minecraft:" + id, volume, pitch);
    }

    /** Address of the server, or an empty string in single player. */
    public static String serverAddress() {
        ServerData server = Minecraft.getMinecraft().getCurrentServerData();
        return server != null ? server.serverIP : "";
    }

    public static String gameDirectory() {
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

    public static boolean setBinding(String name, boolean pressed) {
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
    public static boolean bindingPressed(String name) {
        KeyBinding binding = binding(name);
        return binding != null && binding.pressed;
    }

    /** Whether the key or button the binding is bound to is down right now. */
    public static boolean bindingPhysicallyHeld(String name) {
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

    public record StackView(boolean present, boolean block, String id, int count, int damage, int maxDamage) {
        public static final StackView EMPTY = new StackView(false, false, "", 0, 0, 0);
    }

    /** One inventory slot, as a script sees it; 0..8 is the hotbar, 36..39 armour. */
    public static StackView stack(int slot) {
        ItemStack stack = player().inventory.getStackInSlot(slot);
        if (stack == null) {
            return StackView.EMPTY;
        }
        return new StackView(true, stack.getItem() instanceof ItemBlock, itemId(stack.getItem()),
                stack.stackSize, stack.getItemDamage(), stack.getMaxDamage());
    }

    public static String itemId(Item item) {
        ResourceLocation name = Item.itemRegistry.getNameForObject(item);
        return name != null ? name.getResourcePath() : item.getUnlocalizedName();
    }

    public record EffectView(String id, int amplifier, int duration) {}

    public static List<EffectView> statusEffects(EntityLivingBase entity) {
        List<EffectView> effects = new ArrayList<>();
        for (PotionEffect effect : entity.getActivePotionEffects()) {
            String name = potionNames().get(effect.getPotionID());
            effects.add(new EffectView(name != null ? name : "potion_" + effect.getPotionID(),
                    effect.getAmplifier(), effect.getDuration()));
        }
        return effects;
    }

    /* 1.8.9 already names its potions and enchantments the way the later
     * registries do, so scripts see the same ids on every version. */
    private static Map<Integer, String> potionNames() {
        if (potionNames == null) {
            Map<Integer, String> names = new HashMap<>();
            for (ResourceLocation location : Potion.getPotionLocations()) {
                Potion potion = Potion.getPotionFromResourceLocation(location.toString());
                if (potion != null) {
                    names.put(potion.getId(), location.getResourcePath());
                }
            }
            potionNames = names;
        }
        return potionNames;
    }

    private static Map<Integer, String> enchantmentNames() {
        if (enchantmentNames == null) {
            Map<Integer, String> names = new HashMap<>();
            for (ResourceLocation location : Enchantment.func_181077_c()) {
                Enchantment enchantment = Enchantment.getEnchantmentByLocation(location.toString());
                if (enchantment != null) {
                    names.put(enchantment.effectId, location.getResourcePath());
                }
            }
            enchantmentNames = names;
        }
        return enchantmentNames;
    }

    public enum Slot { HEAD, CHEST, LEGS, FEET, MAIN_HAND, OFF_HAND }

    public record EnchantView(String id, int level) {}

    /** Display name rather than registry path: a renamed sword is called what its owner called it. */
    public record EquipmentView(boolean present, String id, String name, int count, int damage, int maxDamage,
                                Item item, List<EnchantView> enchantments) {
        static final EquipmentView EMPTY = new EquipmentView(false, "", "", 0, 0, 0, null, List.of());
    }

    public static EquipmentView equipment(EntityLivingBase entity, Slot slot) {
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
            String enchantment = enchantmentNames().get(id);
            enchantments.add(new EnchantView(enchantment != null ? enchantment : "enchantment_" + id,
                    tag.getShort("lvl")));
        }
        return new EquipmentView(true, itemId(stack.getItem()), name, stack.stackSize, stack.getItemDamage(),
                stack.getMaxDamage(), stack.getItem(), enchantments);
    }

    /** GL names of textures the game already uploaded. Borrowed, never owned. */
    public record SkinView(int texture, boolean slim) {
        static final SkinView NONE = new SkinView(0, false);
    }

    public static SkinView playerSkin(AbstractClientPlayer player) {
        NetworkPlayerInfo info = player.getPlayerInfo();
        if (info == null) {
            return SkinView.NONE;
        }
        ITextureObject texture = Minecraft.getMinecraft().getTextureManager().getTexture(info.getLocationSkin());
        return new SkinView(texture != null ? texture.getGlTextureId() : 0, "slim".equals(info.getSkinType()));
    }

    public record SpriteView(boolean valid, int texture, float u0, float v0, float u1, float v1) {
        static final SpriteView NONE = new SpriteView(false, 0, 0, 0, 0, 0);
    }

    /**
     * The icon the item's model uses, out of the block atlas items share on
     * 1.8.9. Items without a model of their own get the missing sprite back,
     * which is reported as no sprite.
     */
    public static SpriteView itemSprite(Item item) {
        if (item == null) {
            return SpriteView.NONE;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TextureMap atlas = mc.getTextureMapBlocks();
        TextureAtlasSprite sprite = mc.getRenderItem().getItemModelMesher().getParticleIcon(item);
        if (sprite == null || sprite == atlas.getMissingSprite()) {
            return SpriteView.NONE;
        }
        return new SpriteView(true, atlas.getGlTextureId(),
                sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV());
    }

    public static SpriteView itemSprite(String itemId) {
        return itemSprite(Item.getByNameOrId(itemId));
    }

    private static net.minecraft.util.Vec3 toMinecraft(Vec3 v) {
        return new net.minecraft.util.Vec3(v.x(), v.y(), v.z());
    }

    private static Vec3 fromMinecraft(net.minecraft.util.Vec3 v) {
        return new Vec3(v.xCoord, v.yCoord, v.zCoord);
    }
}
