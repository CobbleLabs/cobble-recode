package cobble.v1_21;

import cobble.core.Maths;
import cobble.core.Vec3;
import cobble.game.BlockKind;
import cobble.game.CrosshairTarget;
import cobble.game.EntityView;
import cobble.game.Face;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.ItemKind;
import cobble.game.LocalPlayerView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.lwjgl.glfw.GLFW;

/** The game as Lunar's 1.21.11 runs it, under Yarn names here and Fabric's intermediary ones at runtime. */
final class GameAdapter implements Game {
    private static final int MAX_CAPTURED_ENTITIES = 320;
    private static final double MAX_CAPTURE_DISTANCE_SQUARED = 256.0 * 256.0;
    private static final int MAX_ENCHANTMENTS = 12;
    private static final int MAX_NAME_LENGTH = 96;
    private static final long STOP_TIMEOUT_SECONDS = 5;
    private static final int OFFHAND_BUTTON = 40;

    /*
     * The window's button callback only schedules this method, which is the
     * one that presses the binding. It is private and runs under its
     * intermediary name, which the build leaves in strings.
     */
    private static final Method ON_MOUSE_BUTTON = mouseButtonHandler();

    private static Method mouseButtonHandler() {
        try {
            Method method = Mouse.class.getDeclaredMethod("method_1601", long.class, MouseInput.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Mouse.onMouseButton not found", e);
        }
    }

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    private static ClientPlayerEntity player() {
        return mc().player;
    }

    private static ClientWorld world() {
        return mc().world;
    }

    private static Direction direction(Face face) {
        return Direction.byIndex(face.ordinal());
    }

    @Override
    public FrameState capture(float partialTicks) {
        MinecraftClient mc = mc();
        Window window = mc.getWindow();
        FrameState state = new FrameState();
        state.windowWidth = window.getFramebufferWidth();
        state.windowHeight = window.getFramebufferHeight();
        state.scaledWidth = window.getScaledWidth();
        state.scaledHeight = window.getScaledHeight();
        state.scaleFactor = window.getScaleFactor();
        state.mouseSensitivity = mc.options.getMouseSensitivity().getValue();
        state.fps = mc.getCurrentFps();
        state.screenOpen = mc.currentScreen != null && !(mc.currentScreen instanceof MenuScreen);
        state.tickProgress = partialTicks;

        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null) {
            return state;
        }
        state.inWorld = true;
        state.worldTime = world.getTime();
        state.matricesValid = Hooks.captureMatrices(state);
        capturePlayer(player, state);
        captureEntities(world, player, state);
        return state;
    }

    private static void capturePlayer(ClientPlayerEntity player, FrameState state) {
        LocalPlayerView view = state.player;
        view.valid = true;
        view.handle = player;
        view.pos = new Vec3(player.getX(), player.getY(), player.getZ());
        view.eyeHeight = player.getStandingEyeHeight();
        view.eyePos = lerpedPos(player, state.tickProgress).add(0.0, view.eyeHeight, 0.0);
        Vec3d velocity = player.getVelocity();
        view.velocity = new Vec3(velocity.x, velocity.y, velocity.z);
        view.yaw = player.getYaw();
        view.pitch = player.getPitch();
        view.lastYaw = player.lastYaw;
        view.lastPitch = player.lastPitch;
        view.health = player.getHealth();
        view.maxHealth = player.getMaxHealth();
        view.armor = player.getArmor();
        view.onGround = player.isOnGround();
        view.sneaking = player.isSneaking();
        view.sprinting = player.isSprinting();
        view.jumping = player.input.playerInput.jump();
        view.hurtTime = player.hurtTime;
        view.maxHurtTime = player.maxHurtTime;
        view.fallDistance = player.fallDistance;
        view.usingItem = player.isUsingItem();
        view.itemUseTicks = player.getItemUseTime();
        view.age = player.age;
        view.attackCharge = player.getAttackCooldownProgress(0.5f);
        state.ping = latency(player.getUuid());
    }

    private static void captureEntities(ClientWorld world, ClientPlayerEntity player, FrameState state) {
        Vec3 self = state.player.pos;
        for (Entity entity : world.getEntities()) {
            if (state.entities.size() >= MAX_CAPTURED_ENTITIES) {
                break;
            }
            if (!(entity instanceof LivingEntity living) || entity == player || !living.isAlive()) {
                continue;
            }
            Vec3 tickPos = new Vec3(entity.getX(), entity.getY(), entity.getZ());
            if (tickPos.sub(self).lengthSquared() > MAX_CAPTURE_DISTANCE_SQUARED) {
                continue;
            }
            EntityView view = new EntityView();
            view.handle = living;
            view.id = entity.getId();
            view.isPlayer = entity instanceof PlayerEntity;
            view.invisible = entity.isInvisible();
            view.sneaking = entity.isSneaking();
            view.onGround = entity.isOnGround();
            view.tickPos = tickPos;
            view.pos = lerpedPos(entity, state.tickProgress);
            view.width = entity.getWidth();
            view.height = entity.getHeight();
            view.eyePos = tickPos.add(0.0, entity.getStandingEyeHeight(), 0.0);
            view.yaw = entity.getYaw();
            view.pitch = entity.getPitch();
            view.bodyYaw = living.bodyYaw;
            view.headYaw = living.getHeadYaw();
            view.health = living.getHealth();
            view.maxHealth = living.getMaxHealth();
            view.absorption = living.getAbsorptionAmount();
            view.armor = living.getArmor();
            view.hurtTime = living.hurtTime;
            view.blocking = living.isBlocking();
            view.distance = view.eyePos.sub(state.player.eyePos).length();
            state.entities.add(view);
        }
    }

    private static Vec3 lerpedPos(Entity entity, float partialTicks) {
        return new Vec3(Maths.lerp(entity.lastX, entity.getX(), partialTicks),
                Maths.lerp(entity.lastY, entity.getY(), partialTicks),
                Maths.lerp(entity.lastZ, entity.getZ(), partialTicks));
    }

    @Override
    public float partialTicks() {
        return mc().getRenderTickCounter().getTickProgress(true);
    }

    @Override
    public boolean paused() {
        return mc().isPaused();
    }

    @Override
    public boolean inWorld() {
        return world() != null;
    }

    @Override
    public void runOnClientThread(Runnable task) throws Exception {
        mc().submit(task).get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public boolean multiplayerScreenOpen() {
        return mc().currentScreen instanceof MultiplayerScreen;
    }

    @Override
    public void setRotation(float yaw, float pitch, boolean alsoPrevious) {
        ClientPlayerEntity player = player();
        float clamped = Maths.clamp(pitch, -90.0f, 90.0f);
        player.setYaw(yaw);
        player.setPitch(clamped);
        if (alsoPrevious) {
            player.lastYaw = yaw;
            player.lastPitch = clamped;
        }
    }

    /* 1.8's mouse counts pitch upward and 1.21's cursor downward. */
    @Override
    public void turn(float yawCounts, float pitchCounts) {
        player().changeLookDirection(yawCounts, -pitchCounts);
    }

    @Override
    public void attackEntity(Object target) {
        MinecraftClient mc = mc();
        mc.interactionManager.attackEntity(mc.player, (Entity) target);
    }

    @Override
    public void setAttackCooldown(int ticks) {
        mc().attackCooldown = ticks;
    }

    @Override
    public int itemUseCooldown() {
        return mc().itemUseCooldown;
    }

    @Override
    public void setItemUseCooldown(int ticks) {
        mc().itemUseCooldown = ticks;
    }

    @Override
    public int blockBreakingCooldown() {
        return mc().interactionManager.blockBreakingCooldown;
    }

    @Override
    public void setBlockBreakingCooldown(int ticks) {
        mc().interactionManager.blockBreakingCooldown = ticks;
    }

    @Override
    public boolean breakingBlock() {
        return mc().interactionManager.isBreakingBlock();
    }

    @Override
    public void swingHand() {
        player().swingHand(Hand.MAIN_HAND);
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
        MinecraftClient mc = mc();
        BlockHitResult target = new BlockHitResult(toMinecraft(hit), direction(face), new BlockPos(x, y, z), false);
        boolean placed = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, target).isAccepted();
        if (placed && swing) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return placed;
    }

    @Override
    public void setCrosshairTarget(int x, int y, int z, Face face, Vec3 hit) {
        mc().crosshairTarget = new BlockHitResult(toMinecraft(hit), direction(face), new BlockPos(x, y, z), false);
    }

    private static BlockState block(int x, int y, int z) {
        return world().getBlockState(new BlockPos(x, y, z));
    }

    @Override
    public boolean isBlockReplaceable(int x, int y, int z) {
        return block(x, y, z).isReplaceable();
    }

    @Override
    public boolean isBlockAir(int x, int y, int z) {
        return block(x, y, z).isAir();
    }

    @Override
    public boolean blockIs(int x, int y, int z, BlockKind kind) {
        BlockState state = block(x, y, z);
        return switch (kind) {
            case OBSIDIAN -> state.isOf(Blocks.OBSIDIAN);
            case BEDROCK -> state.isOf(Blocks.BEDROCK);
            case BED -> state.isIn(net.minecraft.registry.tag.BlockTags.BEDS);
            case RESPAWN_ANCHOR -> state.isOf(Blocks.RESPAWN_ANCHOR);
        };
    }

    @Override
    public String blockId(int x, int y, int z) {
        return Registries.BLOCK.getId(block(x, y, z).getBlock()).getPath();
    }

    @Override
    public float toolSpeed(int slot, int x, int y, int z) {
        return player().getInventory().getStack(slot).getMiningSpeedMultiplier(block(x, y, z));
    }

    @Override
    public boolean boxEmpty(Vec3 minimum, Vec3 maximum) {
        Box box = new Box(minimum.x(), minimum.y(), minimum.z(), maximum.x(), maximum.y(), maximum.z());
        return world().doesNotIntersectEntities(null, VoxelShapes.cuboid(box));
    }

    private static ItemStack heldItem() {
        return player().getMainHandStack();
    }

    @Override
    public boolean handEmpty() {
        return heldItem().isEmpty();
    }

    @Override
    public boolean holdingBlock() {
        return heldItem().getItem() instanceof BlockItem;
    }

    @Override
    public boolean holdingItem(ItemKind kind) {
        return stackIs(heldItem(), kind);
    }

    @Override
    public int findSlot(ItemKind kind, int from, int to) {
        ClientPlayerEntity player = player();
        for (int slot = Math.max(0, from); slot <= Math.min(35, to); ++slot) {
            if (stackIs(player.getInventory().getStack(slot), kind)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean stackIs(ItemStack stack, ItemKind kind) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (kind) {
            case SWORD -> stack.isIn(ItemTags.SWORDS);
            case AXE -> stack.isIn(ItemTags.AXES);
            case OBSIDIAN -> stack.isOf(Items.OBSIDIAN);
            case GLOWSTONE -> stack.isOf(Items.GLOWSTONE);
            case TOTEM -> stack.isOf(Items.TOTEM_OF_UNDYING);
            case END_CRYSTAL -> stack.isOf(Items.END_CRYSTAL);
            case RESPAWN_ANCHOR -> stack.isOf(Items.RESPAWN_ANCHOR);
            case MACE -> stack.isOf(Items.MACE);
            case WIND_CHARGE -> stack.isOf(Items.WIND_CHARGE);
            case ENDER_PEARL -> stack.isOf(Items.ENDER_PEARL);
            case IRON_INGOT -> stack.isOf(Items.IRON_INGOT);
            case BLAZE_POWDER -> stack.isOf(Items.BLAZE_POWDER);
            case GHAST_TEAR -> stack.isOf(Items.GHAST_TEAR);
            case SUGAR -> stack.isOf(Items.SUGAR);
            case FEATHER -> stack.isOf(Items.FEATHER);
            case MAGMA_CREAM -> stack.isOf(Items.MAGMA_CREAM);
            case SPIDER_EYE -> stack.isOf(Items.SPIDER_EYE);
            case GOLDEN_SWORD -> stack.isOf(Items.GOLDEN_SWORD);
        };
    }

    @Override
    public int selectedSlot() {
        return player().getInventory().getSelectedSlot();
    }

    @Override
    public void setSelectedSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        MinecraftClient mc = mc();
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.interactionManager.lastSelectedSlot != slot) {
            mc.interactionManager.lastSelectedSlot = slot;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    @Override
    public boolean swapSlot(int slot, int hotbar) {
        if (slot < 0 || slot > 35 || hotbar < 0 || hotbar > 8) {
            return false;
        }
        MinecraftClient mc = mc();
        ClientPlayerEntity player = mc.player;
        // Container slots 36..44 are the hotbar; 9..35 match the inventory.
        int containerSlot = slot < 9 ? 36 + slot : slot;
        mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, containerSlot, hotbar,
                SlotActionType.SWAP, player);
        return true;
    }

    /* The swap click's button names the off hand by its key slot, 40. */
    @Override
    public boolean swapToOffhand(int slot) {
        if (slot < 0 || slot > 35) {
            return false;
        }
        MinecraftClient mc = mc();
        ClientPlayerEntity player = mc.player;
        int containerSlot = slot < 9 ? 36 + slot : slot;
        mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, containerSlot, OFFHAND_BUTTON,
                SlotActionType.SWAP, player);
        return true;
    }

    @Override
    public int anchorCharges(int x, int y, int z) {
        BlockState state = block(x, y, z);
        return state.isOf(Blocks.RESPAWN_ANCHOR) ? state.get(RespawnAnchorBlock.CHARGES) : -1;
    }

    @Override
    public CrosshairTarget crosshair() {
        HitResult hit = mc().crosshairTarget;
        if (hit == null) {
            return CrosshairTarget.MISS;
        }
        Vec3 point = fromMinecraft(hit.getPos());
        return switch (hit.getType()) {
            case BLOCK -> {
                BlockHitResult block = (BlockHitResult) hit;
                BlockPos pos = block.getBlockPos();
                yield new CrosshairTarget(CrosshairTarget.Kind.BLOCK, pos.getX(), pos.getY(), pos.getZ(),
                        Face.byIndex(block.getSide().getIndex()), point, null, -1, false, false);
            }
            case ENTITY -> {
                Entity entity = ((EntityHitResult) hit).getEntity();
                yield new CrosshairTarget(CrosshairTarget.Kind.ENTITY, 0, 0, 0, Face.UP, point, entity,
                        entity.getId(), entity instanceof EndCrystalEntity, entity instanceof SlimeEntity);
            }
            case MISS -> CrosshairTarget.MISS;
        };
    }

    @Override
    public boolean isZombie(Object entity) {
        return entity instanceof ZombieEntity;
    }

    @Override
    public boolean isPlayer(Object entity) {
        return entity instanceof PlayerEntity;
    }

    @Override
    public int hurtResistance(Object entity) {
        return ((Entity) entity).timeUntilRegen;
    }

    @Override
    public int attackTargetId() {
        LivingEntity target = player().getAttacking();
        return target != null ? target.getId() : -1;
    }

    @Override
    public void attackBlock(int x, int y, int z, Face face, boolean progressOnly) {
        MinecraftClient mc = mc();
        BlockPos pos = new BlockPos(x, y, z);
        if (progressOnly) {
            mc.interactionManager.updateBlockBreakingProgress(pos, direction(face));
        } else {
            mc.interactionManager.attackBlock(pos, direction(face));
        }
    }

    @Override
    public void pressKey(boolean useKey, boolean pressed) {
        GameOptions options = mc().options;
        (useKey ? options.useKey : options.attackKey).setPressed(pressed);
    }

    @Override
    public void queueClick(boolean useKey) {
        GameOptions options = mc().options;
        ++(useKey ? options.useKey : options.attackKey).timesPressed;
    }

    @Override
    public void queueMouseButton(int button, boolean pressed) {
        MinecraftClient mc = mc();
        try {
            ON_MOUSE_BUTTON.invoke(mc.mouse, mc.getWindow().getHandle(), new MouseInput(button, 0),
                    pressed ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mouse button not posted", e);
        }
    }

    /* Each posted event sets the binding the moment it arrives, a frame at a
     * time, and that is what keystroke displays read here already. */
    @Override
    public void showQueuedMouseButton(int button, boolean shown) {
    }

    @Override
    public boolean inGameFocused() {
        MinecraftClient mc = mc();
        return mc.currentScreen == null && mc.mouse.isCursorLocked();
    }

    @Override
    public int quickMoveSlot() {
        MinecraftClient mc = mc();
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler() != mc.player.currentScreenHandler
                || !screen.getScreenHandler().getCursorStack().isEmpty()) {
            return -1;
        }
        net.minecraft.screen.slot.Slot slot = screen.focusedSlot;
        return slot != null && slot.hasStack() && slot.canTakeItems(mc.player) ? slot.id : -1;
    }

    @Override
    public void quickMove(int containerSlot) {
        MinecraftClient mc = mc();
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSlot, 0,
                SlotActionType.QUICK_MOVE, mc.player);
    }

    @Override
    public void openInventory() {
        MinecraftClient mc = mc();
        mc.setScreen(new InventoryScreen(mc.player));
    }

    @Override
    public boolean inventoryOpen() {
        return mc().currentScreen instanceof InventoryScreen;
    }

    @Override
    public void closeScreen() {
        mc().setScreen(null);
    }

    @Override
    public void respawn() {
        player().requestRespawn();
    }

    @Override
    public void sendChat(String text, boolean command) {
        if (command) {
            player().networkHandler.sendChatCommand(text.startsWith("/") ? text.substring(1) : text);
        } else {
            player().networkHandler.sendChatMessage(text);
        }
    }

    @Override
    public void printChat(String text) {
        mc().inGameHud.getChatHud().addMessage(Text.literal(text));
    }

    @Override
    public void overlayMessage(String text) {
        mc().inGameHud.setOverlayMessage(Text.literal(text), false);
    }

    @Override
    public String entityName(Object entity) {
        return ((Entity) entity).getName().getString();
    }

    @Override
    public String entityUuid(Object entity) {
        return ((Entity) entity).getUuid().toString();
    }

    @Override
    public int entityLatency(Object player) {
        return latency(((Entity) player).getUuid());
    }

    private static int latency(UUID id) {
        PlayerListEntry entry = mc().getNetworkHandler() != null ? mc().getNetworkHandler().getPlayerListEntry(id) : null;
        return entry != null ? entry.getLatency() : -1;
    }

    @Override
    public List<EntityRef> enderPearls() {
        return entities(EnderPearlEntity.class);
    }

    @Override
    public List<EntityRef> endCrystals() {
        return entities(EndCrystalEntity.class);
    }

    private static List<EntityRef> entities(Class<? extends Entity> type) {
        List<EntityRef> found = new ArrayList<>();
        for (Entity entity : mc().world.getEntities()) {
            if (type.isInstance(entity)) {
                Vec3d velocity = entity.getVelocity();
                found.add(new EntityRef(entity, entity.getId(), new Vec3(entity.getX(), entity.getY(), entity.getZ()),
                        new Vec3(velocity.x, velocity.y, velocity.z)));
            }
        }
        return found;
    }

    @Override
    public void setVelocity(Object entity, Vec3 velocity) {
        ((Entity) entity).setVelocity(velocity.x(), velocity.y(), velocity.z());
    }

    @Override
    public void playSound(String id, float volume, float pitch) {
        SoundEvent sound = Registries.SOUND_EVENT.get(Identifier.of(id.indexOf(':') >= 0 ? id : "minecraft:" + id));
        if (sound != null) {
            player().playSound(sound, volume, pitch);
        }
    }

    @Override
    public String serverAddress() {
        ServerInfo server = mc().getCurrentServerEntry();
        return server != null ? server.address : "";
    }

    @Override
    public String gameDirectory() {
        return mc().runDirectory.getAbsolutePath();
    }

    private static KeyBinding binding(String name) {
        GameOptions options = mc().options;
        return switch (name) {
            case "forward" -> options.forwardKey;
            case "back" -> options.backKey;
            case "left" -> options.leftKey;
            case "right" -> options.rightKey;
            case "jump" -> options.jumpKey;
            case "sneak" -> options.sneakKey;
            case "sprint" -> options.sprintKey;
            case "attack" -> options.attackKey;
            case "use" -> options.useKey;
            default -> null;
        };
    }

    @Override
    public boolean setBinding(String name, boolean pressed) {
        KeyBinding binding = binding(name);
        if (binding == null) {
            return false;
        }
        binding.setPressed(pressed);
        return true;
    }

    @Override
    public boolean bindingPressed(String name) {
        KeyBinding binding = binding(name);
        return binding != null && binding.isPressed();
    }

    @Override
    public boolean bindingPhysicallyHeld(String name) {
        KeyBinding binding = binding(name);
        if (binding == null) {
            return false;
        }
        long window = mc().getWindow().getHandle();
        InputUtil.Key key = binding.boundKey;
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() > 0
                && GLFW.glfwGetKey(window, key.getCode()) == GLFW.GLFW_PRESS;
    }

    @Override
    public StackView stack(int slot) {
        ItemStack stack = player().getInventory().getStack(slot);
        if (stack.isEmpty()) {
            return StackView.EMPTY;
        }
        return new StackView(true, stack.getItem() instanceof BlockItem, itemId(stack.getItem()),
                stack.getCount(), stack.getDamage(), stack.getMaxDamage());
    }

    private static String itemId(Item item) {
        return Registries.ITEM.getId(item).getPath();
    }

    @Override
    public List<EffectView> statusEffects(Object entity) {
        List<EffectView> effects = new ArrayList<>();
        for (StatusEffectInstance effect : ((LivingEntity) entity).getStatusEffects()) {
            String name = effect.getEffectType().getKey().map(key -> key.getValue().getPath()).orElse("unknown");
            effects.add(new EffectView(name, effect.getAmplifier(), effect.getDuration()));
        }
        return effects;
    }

    @Override
    public EquipmentView equipment(Object handle, Slot slot) {
        LivingEntity entity = (LivingEntity) handle;
        ItemStack stack = entity.getEquippedStack(switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case MAIN_HAND -> EquipmentSlot.MAINHAND;
            case OFF_HAND -> EquipmentSlot.OFFHAND;
        });
        if (stack.isEmpty()) {
            return EquipmentView.EMPTY;
        }
        String name = Formatting.strip(stack.getName().getString());
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        List<EnchantView> enchantments = new ArrayList<>();
        ItemEnchantmentsComponent component = EnchantmentHelper.getEnchantments(stack);
        for (var entry : component.getEnchantmentEntries()) {
            if (enchantments.size() >= MAX_ENCHANTMENTS) {
                break;
            }
            String id = entry.getKey().getKey().map(key -> key.getValue().getPath()).orElse("unknown");
            enchantments.add(new EnchantView(id, entry.getIntValue()));
        }
        return new EquipmentView(true, itemId(stack.getItem()), name, stack.getCount(), stack.getDamage(),
                stack.getMaxDamage(), enchantments);
    }

    /* The skin lives in a GPU texture the game's own device owns; the radar draws a head without it. */
    @Override
    public SkinView playerSkin(Object player) {
        return SkinView.NONE;
    }

    /* Items draw from baked models rather than one atlas sprite each; the radar falls back to its own icons. */
    @Override
    public SpriteView itemSprite(String itemId) {
        return SpriteView.NONE;
    }

    private static Vec3d toMinecraft(Vec3 v) {
        return new Vec3d(v.x(), v.y(), v.z());
    }

    private static Vec3 fromMinecraft(Vec3d v) {
        return new Vec3(v.x, v.y, v.z);
    }
}
