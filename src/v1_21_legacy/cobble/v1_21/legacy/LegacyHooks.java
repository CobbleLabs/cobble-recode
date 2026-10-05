package cobble.v1_21.legacy;

import cobble.event.ChangeCurrentItemEvent;
import cobble.event.ChatReceivedEvent;
import cobble.event.ClickMouseEvent;
import cobble.event.DisconnectEvent;
import cobble.event.EntityJoinWorldEvent;
import cobble.event.EventBus;
import cobble.event.ExplosionEvent;
import cobble.event.GameLoopEvent;
import cobble.event.GuiOpenEvent;
import cobble.event.JumpEvent;
import cobble.event.LeftClickEvent;
import cobble.event.LoadWorldEvent;
import cobble.event.MouseEvent;
import cobble.event.MoveEvent;
import cobble.event.MoveFlyingEvent;
import cobble.event.MoveInputEvent;
import cobble.event.MoveStateUpdateEvent;
import cobble.event.PacketReceiveEvent;
import cobble.event.PacketSendEvent;
import cobble.event.Phase;
import cobble.event.PlayerAttackEvent;
import cobble.event.PostMoveEvent;
import cobble.event.PostUpdateEvent;
import cobble.event.PostWalkingUpdateEvent;
import cobble.event.PrePlayerInteractEvent;
import cobble.event.PreUpdateEvent;
import cobble.event.RenderTickEvent;
import cobble.event.RenderWorldLastEvent;
import cobble.event.RightClickEvent;
import cobble.event.SetCurrentItemEvent;
import cobble.event.TickEndEvent;
import cobble.event.TickStartEvent;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.event.VelocityEvent;
import cobble.game.RotationEngine;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.combat.KeepSprint;
import cobble.module.minigames.NukeDefend;
import cobble.module.player.BedNuker;
import cobble.module.player.BridgeNuker;
import cobble.module.player.Freecam;
import cobble.module.player.InventoryManager;
import cobble.module.render.AntiDebuff;
import cobble.module.render.AntiInvis;
import cobble.module.render.Cape;
import cobble.module.render.Chams;
import cobble.module.render.FreeLook;
import cobble.module.render.Nametags;
import cobble.module.render.Xray;
import cobble.util.packet.PacketUtils;
import cobble.util.raytrace.BlockNuker;
import cobble.util.raytrace.PointedObjectOverrider;
import cobble.util.rotation.EntityRotationOverride;
import cobble.util.rotation.RotationHandler;
import cobble.util.rotation.RotationUtils;
import cobble.v1_21.compat.RenderPasses;
import cobble.v1_21.compat.S27PacketExplosionShim;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * The handlers {@link LegacyHookTable} calls, raising the legacy events the
 * way 1.8.9's {@code cobble.hook.Hooks} does. Where 1.8 replaced a vanilla
 * step with its own copy so modules could change its inputs, this does the
 * same with 1.21's step.
 */
public final class LegacyHooks {
    private static final double JUMP_MINIMUM = 1.0E-5;
    /* getJumpVelocity is protected; it runs under its intermediary name, which the build leaves in strings. */
    private static final Method JUMP_VELOCITY = jumpVelocity();

    private static float blockDamageBefore;
    private static float walkingYaw;
    private static float walkingPitch;
    private static boolean openingReplacement;
    private static final EntityRotationOverride freeLookRotation = new EntityRotationOverride();
    private static Entity freeLookRotated;

    private LegacyHooks() {}

    private static Method jumpVelocity() {
        try {
            Method method = LivingEntity.class.getDeclaredMethod("method_6106");
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("LivingEntity.getJumpVelocity not found", e);
        }
    }

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    public static void tickStart() {
        EventBus.post(new TickStartEvent());
        EventBus.post(new TickEndEvent(Phase.START));
    }

    public static void targetRotation() {
        RotationHandler.getInstance().updateTargetRotation();
    }

    /** The crosshair ray follows the silent rotation, so clicks land where the server sees the player look. */
    public static void pickStart() {
        RotationHandler rotations = RotationHandler.getInstance();
        Entity viewer = mc().getCameraEntity();
        if (rotations.renderRotationApplied || viewer == null || !rotations.abxpJn()) {
            return;
        }
        Float yaw = rotations.getTargetYaw();
        Float pitch = rotations.cvZx();
        if (yaw != null && pitch != null && !yaw.isNaN() && !pitch.isNaN()) {
            rotations.mNwrQ(viewer, yaw, pitch, true);
            rotations.renderRotationApplied = true;
        }
    }

    /*
     * The first module that wants the crosshair gets it; one breaking blocks
     * ahead of the rest, the order 1.8.9's hook asked them in.
     */
    public static void pointedObject(float partialTicks) {
        for (boolean nukers : new boolean[] {true, false}) {
            for (Module module : ModuleManager.all()) {
                if (module instanceof PointedObjectOverrider overrider && module instanceof BlockNuker == nukers
                        && overrider.shouldOverridePointedObject()) {
                    overrider.applyPointedObjectOverride(partialTicks);
                    return;
                }
            }
        }
    }

    public static void pickEnd() {
        RotationHandler rotations = RotationHandler.getInstance();
        if (rotations.renderRotationApplied) {
            Entity viewer = mc().getCameraEntity();
            if (viewer != null) {
                rotations.restoreEntityRotation(viewer);
            }
            rotations.renderRotationApplied = false;
        }
    }

    public static void controllerUpdated() {
        ModuleManager.get(BedNuker.class).processPendingPlacement();
    }

    public static void blockClickSent() {
        BedNuker nuker = ModuleManager.get(BedNuker.class);
        if (nuker.isNuking()) {
            nuker.QLkR();
        }
    }

    public static boolean blockBreaking() {
        return Freecam.cameraEntity != null;
    }

    public static void clickHitDelay(ClientPlayerInteractionManager controller) {
        shortenHitDelay(controller);
    }

    public static void damageBlock(ClientPlayerInteractionManager controller) {
        blockDamageBefore = controller.currentBreakingProgress;
    }

    public static void breakHitDelay(ClientPlayerInteractionManager controller) {
        shortenHitDelay(controller);
    }

    public static void blockDamaged(ClientPlayerInteractionManager controller) {
        float gained = controller.currentBreakingProgress - blockDamageBefore;
        float multiplier = breakSpeedMultiplier();
        if (gained > 0.0f && multiplier > 1.0f) {
            controller.currentBreakingProgress = Math.min(1.0f, blockDamageBefore + gained * multiplier);
        }
    }

    private static float breakSpeedMultiplier() {
        BedNuker bedNuker = ModuleManager.get(BedNuker.class);
        if (bedNuker.isNuking()) {
            return bedNuker.TnokwE();
        }
        BridgeNuker bridgeNuker = ModuleManager.get(BridgeNuker.class);
        return bridgeNuker.PfjH() ? bridgeNuker.getBreakSpeedMultiplier() : 1.0f;
    }

    private static void shortenHitDelay(ClientPlayerInteractionManager controller) {
        BridgeNuker bridgeNuker = ModuleManager.get(BridgeNuker.class);
        if (ModuleManager.get(BedNuker.class).isNuking()) {
            controller.blockBreakingCooldown = 0;
        } else if (bridgeNuker.PfjH() && bridgeNuker.getBreakDelayTicks() < 5) {
            controller.blockBreakingCooldown = bridgeNuker.getBreakDelayTicks();
        }
    }

    /* A position outside the world border is a placement refused before anything is sent. */
    public static boolean placeBlock(WorldBorder border, BlockPos pos) {
        return !ModuleManager.get(NukeDefend.class).shouldBlockDuplicatePlacement() && border.contains(pos);
    }

    public static boolean keepSprintAttack(PlayerEntity player, Entity target) {
        KeepSprint keepSprint = ModuleManager.get(KeepSprint.class);
        return Freecam.cameraEntity != null || keepSprint.enabled() && keepSprint.shouldCancelAttack(player, target);
    }

    /* Vanilla's sprint hit slowdown, which KeepSprint replaces with its own. */
    public static void attackMotion(PlayerEntity player, Vec3d motion) {
        if (!keepsSprint(player)) {
            player.setVelocity(motion);
        }
    }

    public static void attackSprint(PlayerEntity player, boolean sprinting) {
        if (keepsSprint(player)) {
            ModuleManager.get(KeepSprint.class).applySprintSlowdown(player);
        } else {
            player.setSprinting(sprinting);
        }
    }

    private static boolean keepsSprint(PlayerEntity player) {
        return player == mc().player && ModuleManager.get(KeepSprint.class).enabled();
    }

    public static void livingUpdate(ClientPlayerEntity player) {
        KeepSprint keepSprint = ModuleManager.get(KeepSprint.class);
        if (keepSprint.enabled()) {
            keepSprint.applyPredictedSprintState(player);
        }
    }

    /* 1.21 turns by cursor deltas whose pitch runs downward; 1.8's turn took it upward. */
    /* 1.21 turns pitch the other way from 1.8's setAngles, which the legacy code takes its turns in. */
    public static boolean turn(Entity entity, double yaw, double pitch) {
        if (entity != mc().player) {
            return false;
        }
        FreeLook freeLook = ModuleManager.get(FreeLook.class);
        if (freeLook.turnsCamera()) {
            freeLook.turnCamera((float) yaw, (float) -pitch);
            return true;
        }
        return Freecam.applyCameraAngles((float) yaw, (float) -pitch);
    }

    /*
     * Xray, on Sodium's chunk builder and on the game's own for when Sodium
     * is off. They run on the builder's worker threads, so they only read
     * Xray's snapshot. A hidden block or fluid is never built, every face of
     * a kept block is (only those against air in cave mode), and night vision
     * lights the world, as a buried ore has no light of its own.
     */
    public static boolean xrayModel(BlockStateModel model, BlockState state) {
        return Xray.hides(state.getBlock());
    }

    public static boolean xrayFluidEmpty(FluidState fluid) {
        return fluid.isEmpty() || Xray.hides(fluid.getBlockState().getBlock());
    }

    public static boolean xrayBlock(BlockState state) {
        return Xray.hides(state.getBlock());
    }

    public static boolean xrayVanillaFluid(BlockPos pos, BlockRenderView world, VertexConsumer consumer,
                                           BlockState state) {
        return Xray.hides(state.getBlock());
    }

    public static boolean xrayFaceCulled(boolean culled) {
        return culled && !Xray.drawsEveryFace();
    }

    public static boolean xraySideDrawn(boolean drawn) {
        return drawn || Xray.drawsEveryFace();
    }

    public static boolean xrayNightVision(ClientPlayerEntity player, RegistryEntry<StatusEffect> effect) {
        return effect == StatusEffects.NIGHT_VISION && Xray.active() || player.hasStatusEffect(effect);
    }

    /*
     * The strength reads the effect it was told is there; Lunar calls it in the
     * lightmap's place, so it is answered inside: an endless effect is full strength.
     */
    public static StatusEffectInstance xrayVisionEffect(LivingEntity entity, RegistryEntry<StatusEffect> effect) {
        StatusEffectInstance real = entity.getStatusEffect(effect);
        return real == null && effect == StatusEffects.NIGHT_VISION && Xray.active()
                ? new StatusEffectInstance(effect, StatusEffectInstance.INFINITE) : real;
    }

    /* An invisible player AntiInvis shows is drawn see-through, as a spectator sees one. */
    public static boolean invisibleTo(boolean invisible, Entity entity) {
        return invisible && !(entity instanceof LivingEntity living
                && ModuleManager.get(AntiInvis.class).isForceVisibleTarget(living));
    }

    public static boolean outlined(boolean outlined, Entity entity) {
        return outlined || ModuleManager.get(Chams.class).outlines(entity);
    }

    public static SkinTextures capeSkin(SkinTextures skin, AbstractClientPlayerEntity player) {
        Identifier cape = player == mc().player ? Cape.texture() : null;
        return cape == null ? skin : new SkinTextures(skin.body(), new AssetInfo.TextureAssetInfo(cape, cape),
                skin.elytra(), skin.model(), skin.secure());
    }

    /*
     * Lunar draws a cloak you wear with its own renderer, and keeps the game's
     * cape layer from drawing under it; while Cobble's cape is on, Lunar is
     * told you wear none. The method is Lunar's, merged into PlayerEntity.
     */
    public static List<?> wornCosmetics(List<?> worn, PlayerEntity player) {
        return player == mc().player && Cape.selected() ? LunarCloaks.withoutCloaks(worn) : worn;
    }

    /** The cape layer only draws for a player wearing the cape part, which the skin settings may switch off. */
    public static boolean capePart(boolean worn, PlayerLikeEntity player, PlayerModelPart part) {
        return worn || part == PlayerModelPart.CAPE && player == mc().player && Cape.selected();
    }

    /* Blindness, and darkness through the same check, thicken the fog only while the effect is there. */
    public static boolean effectFog(LivingEntity entity, RegistryEntry<StatusEffect> effect) {
        return !ModuleManager.get(AntiDebuff.class).blocksBlindness(effect) && entity.hasStatusEffect(effect);
    }

    /* How far nausea has faded in drives both the wobble and the overlay. */
    public static float nauseaFade(ClientPlayerEntity player, RegistryEntry<StatusEffect> effect, float tickDelta) {
        return ModuleManager.get(AntiDebuff.class).blocksConfusion(effect) ? 0.0f
                : player.getEffectFadeFactor(effect, tickDelta);
    }

    /* The camera takes the entity's rotation as it is placed; free look's stands in for that long. */
    public static void cameraStart(World world, Entity entity) {
        if (FreeLook.DnH && ModuleManager.get(FreeLook.class).enabled()) {
            freeLookRotation.applyRotation(entity, FreeLook.savedYaw, FreeLook.savedPitch, true);
            freeLookRotated = entity;
        }
    }

    public static void cameraEnd() {
        if (freeLookRotated != null) {
            freeLookRotation.oIpxn(freeLookRotated);
            freeLookRotated = null;
        }
    }

    public static boolean motionViewEntity(ClientPlayerEntity player) {
        return mc().getCameraEntity() == player || Freecam.cameraEntity != null;
    }

    public static boolean inputViewEntity(ClientPlayerEntity player) {
        return mc().getCameraEntity() == player || Freecam.cameraEntity != null;
    }

    public static void closeScreen() {
        ModuleManager.get(InventoryManager.class).recoverCursorOnScreenClose("ClientPlayerEntity.closeHandledScreen");
    }

    /** The player's own clicks would scramble a sort InventoryManager is in the middle of. */
    public static boolean containerClick() {
        return ModuleManager.inventoryBusy();
    }

    public static boolean containerDrag() {
        return ModuleManager.inventoryBusy();
    }

    public static boolean containerRelease() {
        return ModuleManager.inventoryBusy();
    }

    public static boolean slotClick() {
        return ModuleManager.inventoryBusy();
    }

    public static boolean canRenderName(Entity entity, double distance) {
        return entity instanceof PlayerEntity player && ModuleManager.get(Nametags.class).shouldHideVanillaNametag(player);
    }

    public static void legacyTickEnd() {
        EventBus.post(new TickEndEvent(Phase.END));
    }

    public static void preInteract() {
        EventBus.post(new PrePlayerInteractEvent());
    }

    public static void setCurrentItem(PlayerInventory inventory, int slot) {
        if (!EventBus.post(new SetCurrentItemEvent(slot)).cancelled()) {
            inventory.setSelectedSlot(slot);
        }
    }

    /* 1.8 raised the wheel's direction; 1.21 hands over the slot it scrolled to. */
    public static void scrollCurrentItem(PlayerInventory inventory, int slot) {
        int current = inventory.getSelectedSlot();
        int size = PlayerInventory.getHotbarSize();
        int direction = Math.floorMod(slot - current, size) == size - 1 ? 1 : -1;
        if (!EventBus.post(new ChangeCurrentItemEvent(direction, current)).cancelled()) {
            inventory.setSelectedSlot(slot);
        }
    }

    public static void gameLoop() {
        EventBus.post(new GameLoopEvent());
        EventBus.post(new RenderTickEvent(Phase.START, mc().getRenderTickCounter().getTickProgress(true)));
    }

    /* Where 1.8 ended its render tick the GUI's projection was up, which the legacy HUD draws with. */
    public static void renderTickEnd(RenderTickCounter counter, boolean tick) {
        RenderPasses.beginGui();
        EventBus.post(new RenderTickEvent(Phase.END, counter.getTickProgress(true)));
    }

    public static boolean leftClick() {
        return Freecam.cameraEntity != null || EventBus.post(new MouseEvent(0, true, 0, 0, 0, 0, 0)).cancelled()
                || EventBus.post(new ClickMouseEvent(mc().crosshairTarget)).cancelled()
                || EventBus.post(new LeftClickEvent()).cancelled();
    }

    public static boolean rightClick() {
        if (Freecam.cameraEntity != null) {
            return true;
        }
        boolean held = GLFW.glfwGetMouseButton(mc().getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                == GLFW.GLFW_PRESS;
        if (held && EventBus.post(new MouseEvent(1, true, 0, 0, 0, 0, 0)).cancelled()) {
            return true;
        }
        return EventBus.post(new RightClickEvent()).cancelled();
    }

    public static void loadWorld(ClientWorld world) {
        EventBus.post(new LoadWorldEvent(world));
    }

    /* 1.8 loaded a null world on the way out; 1.21 only disconnects. */
    public static void leaveWorld() {
        if (mc().world != null) {
            EventBus.post(new LoadWorldEvent(null));
        }
    }

    /* A screen a module put in the event's place is opened instead, without asking the modules again. */
    public static boolean openGui(Screen screen) {
        if (openingReplacement) {
            return false;
        }
        GuiOpenEvent event = EventBus.post(new GuiOpenEvent(screen));
        if (event.cancelled() || event.guiScreen == screen) {
            return event.cancelled();
        }
        openingReplacement = true;
        try {
            mc().setScreen(event.guiScreen);
        } finally {
            openingReplacement = false;
        }
        return true;
    }

    public static void preUpdate() {
        EventBus.post(new PreUpdateEvent());
    }

    public static void postUpdate(ClientPlayerEntity player) {
        if (player.getEntityWorld().isPosLoaded(player.getBlockPos())) {
            EventBus.post(new PostUpdateEvent());
        }
    }

    public static void legacyPreMotion(ClientPlayerEntity player) {
        float[] silent = RotationEngine.preMotion(player.getYaw(), player.getPitch());
        if (silent != null) {
            player.setYaw(silent[0]);
            player.setPitch(silent[1]);
        }
        walkingYaw = player.getYaw();
        walkingPitch = player.getPitch();
        /* 1.21 sends sneaking with the input packet, ahead of this one; the event's sneak flag has nothing to change. */
        UpdateWalkingPlayerEvent event = EventBus.post(
                new UpdateWalkingPlayerEvent(walkingYaw, walkingPitch, player.isSneaking()));
        player.setYaw(event.getYaw());
        player.setPitch(event.getPitch());
        RotationUtils.lastSentRotation[0] = event.getYaw();
        RotationUtils.lastSentRotation[1] = event.getPitch();
    }

    public static void legacyPostMotion(ClientPlayerEntity player) {
        player.setYaw(walkingYaw);
        player.setPitch(walkingPitch);
        EventBus.post(new PostWalkingUpdateEvent());
        float[] restored = RotationEngine.postMotion(player.lastYawClient, player.lastPitchClient);
        if (restored != null) {
            player.setYaw(restored[0]);
            player.setPitch(restored[1]);
        }
    }

    /** 1.21's jump for the local player, with the motion, yaw and sprint the modules chose. */
    public static boolean jump(LivingEntity entity) {
        if (entity != mc().player) {
            return false;
        }
        float velocity;
        try {
            velocity = (float) JUMP_VELOCITY.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("jump velocity unreadable", e);
        }
        JumpEvent event = EventBus.post(new JumpEvent(entity, velocity, entity.getYaw(), entity.isSprinting()));
        if (event.cancelled()) {
            return true;
        }
        if (event.getMotionY() <= JUMP_MINIMUM) {
            return true;
        }
        Vec3d motion = entity.getVelocity();
        entity.setVelocity(motion.x, Math.max(event.getMotionY(), motion.y), motion.z);
        if (event.isSprinting()) {
            float yaw = event.getYaw() * MathHelper.RADIANS_PER_DEGREE;
            entity.addVelocityInternal(new Vec3d(-MathHelper.sin(yaw) * 0.2, 0.0, MathHelper.cos(yaw) * 0.2));
        }
        entity.velocityDirty = true;
        return true;
    }

    /** 1.21's updateVelocity for the local player, with the inputs and yaw the modules chose. */
    public static boolean moveFlying(Entity entity, float speed, Vec3d input) {
        if (entity != mc().player) {
            return false;
        }
        MoveFlyingEvent event = EventBus.post(new MoveFlyingEvent((float) input.x, (float) input.z, speed,
                entity.getYaw()));
        Vec3d moved = new Vec3d(event.getStrafe(), input.y, event.getForward());
        double length = moved.lengthSquared();
        if (length < 1.0E-7) {
            return true;
        }
        Vec3d scaled = (length > 1.0 ? moved.normalize() : moved).multiply(event.getFriction());
        float sin = MathHelper.sin(event.getYaw() * MathHelper.RADIANS_PER_DEGREE);
        float cos = MathHelper.cos(event.getYaw() * MathHelper.RADIANS_PER_DEGREE);
        entity.setVelocity(entity.getVelocity().add(scaled.x * cos - scaled.z * sin, scaled.y,
                scaled.z * cos + scaled.x * sin));
        return true;
    }

    public static void moveEntity(LivingEntity entity, MovementType type, Vec3d movement) {
        move(entity, type, movement);
    }

    public static void moveEntityInWater(LivingEntity entity, MovementType type, Vec3d movement) {
        move(entity, type, movement);
    }

    public static void moveEntityInLava(LivingEntity entity, MovementType type, Vec3d movement) {
        move(entity, type, movement);
    }

    private static void move(LivingEntity entity, MovementType type, Vec3d movement) {
        if (entity != mc().player) {
            entity.move(type, movement);
            return;
        }
        MoveEvent event = EventBus.post(new MoveEvent(movement.x, movement.y, movement.z));
        entity.move(type, new Vec3d(event.getX(), event.getY(), event.getZ()));
        EventBus.post(new PostMoveEvent());
    }

    public static void moveInput(KeyboardInput input) {
        MoveInputEvent event = EventBus.post(new MoveInputEvent(input.movementVector.y, input.movementVector.x,
                input.playerInput.jump(), input.playerInput.sneak()));
        input.movementVector = new Vec2f(event.getMoveStrafe(), event.getMoveForward());
        PlayerInput keys = input.playerInput;
        input.playerInput = new PlayerInput(keys.forward(), keys.backward(), keys.left(),
                keys.right(), event.isJumping(), event.isSneaking(), keys.sprint());
        EventBus.post(new MoveStateUpdateEvent());
    }

    public static boolean sendPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        NukeDefend nukeDefend = ModuleManager.get(NukeDefend.class);
        if (packet instanceof PlayerInteractBlockC2SPacket && nukeDefend.MuoLy()) {
            PacketUtils.consumeSilentlySent(packet);
            return true;
        }
        if (!PacketUtils.consumeSilentlySent(packet) && EventBus.post(new PacketSendEvent(packet)).cancelled()) {
            return true;
        }
        if (packet instanceof PlayerMoveC2SPacket) {
            nukeDefend.clearPendingPlacement();
        }
        return false;
    }

    public static boolean receivePacket(ChannelHandlerContext context, Packet<?> packet) {
        return EventBus.post(new PacketReceiveEvent(packet)).cancelled();
    }

    /*
     * The packet handlers start by rescheduling themselves onto the game
     * thread when they run on netty's, so these are reached twice; only the
     * game thread's pass raises the event.
     */
    public static boolean chat(GameMessageS2CPacket packet) {
        return mc().isOnThread() && EventBus.post(new ChatReceivedEvent((byte) (packet.overlay() ? 2 : 1),
                packet.content())).cancelled();
    }

    public static void disconnect() {
        EventBus.post(new DisconnectEvent());
    }

    public static boolean velocity(EntityVelocityUpdateS2CPacket packet) {
        return mc().isOnThread() && EventBus.post(new VelocityEvent(packet)).cancelled();
    }

    public static void explosion(ExplosionS2CPacket packet) {
        if (mc().isOnThread()) {
            EventBus.post(new ExplosionEvent(packet));
        }
    }

    /* A push the modules wrote into the packet replaces the one it came with. */
    public static Optional<Vec3d> explosionPush(ExplosionS2CPacket packet) {
        return S27PacketExplosionShim.pushWritten(packet) ? Optional.of(S27PacketExplosionShim.push(packet))
                : packet.playerKnockback();
    }

    public static void entityJoined(ClientWorld world, Entity entity) {
        EventBus.post(new EntityJoinWorldEvent(entity, world));
    }

    public static boolean playerAttack(PlayerEntity player, Entity target) {
        return EventBus.post(new PlayerAttackEvent(player, target)).cancelled();
    }

    public static void renderWorldLast(Object allocator, RenderTickCounter counter, boolean outline, Camera camera,
                                       Matrix4f positionMatrix, Matrix4f projectionMatrix) {
        RenderPasses.beginWorld(camera, positionMatrix, projectionMatrix);
        if (EventBus.hasListeners(RenderWorldLastEvent.class)) {
            EventBus.post(new RenderWorldLastEvent(counter.getTickProgress(true)));
        }
    }
}
