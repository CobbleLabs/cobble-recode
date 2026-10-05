package cobble.v1_21.legacy;

import static cobble.boot.Hook.of;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;

/**
 * Where 1.21.11 raises the events the legacy code listens for, at the points
 * 1.8.9's {@code cobble.hook.HookTable} raises them. Intermediary names, as in
 * {@code cobble.v1_21.HookTable}, with the Yarn name of each beside it. The
 * motion pair replaces the base one, which only knew the silent rotations.
 */
final class LegacyHookTable {
    private static final String MINECRAFT = "net/minecraft/class_310";              // MinecraftClient
    private static final String GAME_RENDERER = "net/minecraft/class_757";          // GameRenderer
    private static final String WORLD_RENDERER = "net/minecraft/class_761";         // WorldRenderer
    private static final String PLAYER = "net/minecraft/class_746";                 // ClientPlayerEntity
    private static final String CLIENT_PLAYER = "net/minecraft/class_742";          // AbstractClientPlayerEntity
    private static final String LIVING = "net/minecraft/class_1309";                // LivingEntity
    private static final String ENTITY = "net/minecraft/class_1297";                // Entity
    private static final String ENTITY_PLAYER = "net/minecraft/class_1657";         // PlayerEntity
    private static final String KEYBOARD_INPUT = "net/minecraft/class_743";         // KeyboardInput
    private static final String CONNECTION = "net/minecraft/class_2535";            // ClientConnection
    private static final String NET_HANDLER = "net/minecraft/class_634";            // ClientPlayNetworkHandler
    private static final String COMMON_NET_HANDLER = "net/minecraft/class_8673";    // ClientCommonNetworkHandler
    private static final String WORLD = "net/minecraft/class_638";                  // ClientWorld
    private static final String MOUSE = "net/minecraft/class_312";                  // Mouse
    private static final String INVENTORY = "net/minecraft/class_1661";             // PlayerInventory

    private static final String CONTROLLER = "net/minecraft/class_636";             // ClientPlayerInteractionManager
    private static final String HANDLED_SCREEN = "net/minecraft/class_465";         // HandledScreen
    private static final String ENTITY_RENDERER = "net/minecraft/class_897";        // EntityRenderer
    private static final String CAMERA = "net/minecraft/class_4184";                // Camera
    private static final String PLAYER_LIKE = "net/minecraft/class_11890";          // PlayerLikeEntity
    private static final String BLOCK_RENDERER = "net/minecraft/class_776";         // BlockRenderManager
    private static final String BLOCK = "net/minecraft/class_2248";                 // Block
    private static final String LIGHTMAP = "net/minecraft/class_765";               // LightmapTextureManager
    /* Sodium, which Lunar builds the world with; its hooks do nothing where it is not loaded. */
    private static final String SODIUM_BLOCKS =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer";
    private static final String SODIUM_CONTEXT = "net/caffeinemc/mods/sodium/client/render/model/AbstractBlockRenderContext";
    private static final String SODIUM_MESHING =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask";
    private static final String SODIUM_EXECUTE = "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";
    private static final String EFFECT_FOG = "net/minecraft/class_7286";            // StatusEffectFogModifier
    private static final String HUD = "net/minecraft/class_329";                    // InGameHud
    private static final String HAS_EFFECT = "(Lnet/minecraft/class_6880;)Z";
    private static final String EFFECT_FADE = "(Lnet/minecraft/class_6880;F)F";
    private static final String PACKET = "Lnet/minecraft/class_2596;";
    private static final String BLOCK_ACTION = "(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z";
    private static final String COOLDOWN = "field_3716";                            // blockBreakingCooldown
    private static final String MOVE = "(Lnet/minecraft/class_1313;Lnet/minecraft/class_243;)V";

    private static final Map<String, Hook> REPLACED = Map.of(
            "preMotion", of(PLAYER, "method_3136", "()V", At.HEAD, "legacyPreMotion").withSelf(),     // sendMovementPackets
            "postMotion", of(PLAYER, "method_3136", "()V", At.RETURN, "legacyPostMotion").withSelf());

    private static final List<Hook> ADDED = List.of(
            /* The modules that drive the block breaker. */
            of(MINECRAFT, "method_1574", "()V",
                    At.after(Opcodes.INVOKEVIRTUAL, CONTROLLER, "method_2927", "()V", 0), "controllerUpdated"),   // tick
            of(MINECRAFT, "method_1508", "()V",
                    At.after(Opcodes.INVOKEVIRTUAL, MINECRAFT, "method_1590", "(Z)V", 0), "blockClickSent"),     // handleBlockBreaking
            of(MINECRAFT, "method_1590", "(Z)V", At.HEAD, "blockBreaking").cancelling(),
            of(CONTROLLER, "method_2910", BLOCK_ACTION,                                                  // attackBlock
                    At.after(Opcodes.PUTFIELD, CONTROLLER, COOLDOWN, "I", 0), "clickHitDelay").withSelf(),
            of(CONTROLLER, "method_2902", BLOCK_ACTION, At.HEAD, "damageBlock").withSelf(),             // updateBlockBreakingProgress
            of(CONTROLLER, "method_2902", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, COOLDOWN, "I", -1), "breakHitDelay").withSelf(),
            of(CONTROLLER, "method_2902", BLOCK_ACTION, At.RETURN, "blockDamaged").withSelf(),
            /* Inside the placement, as Lunar wraps the call doItemUse makes to it. */
            of(CONTROLLER, "method_2896",                                                                  // interactBlock
                    "(Lnet/minecraft/class_746;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;",
                    At.redirect(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2784", "method_11952",       // WorldBorder.contains
                            "(Lnet/minecraft/class_2338;)Z", 0), "placeBlock"),
            of(CONTROLLER, "method_2918", "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1297;)V",     // attackEntity
                    At.HEAD, "keepSprintAttack").withArgs().cancelling(),

            /* KeepSprint's hit slowdown, and the sprint it predicts. */
            of(ENTITY_PLAYER, "method_75122", "(Lnet/minecraft/class_1297;FLnet/minecraft/class_243;)V",  // knockbackTarget
                    At.redirect(Opcodes.INVOKEVIRTUAL, ENTITY_PLAYER, "method_18799", "(Lnet/minecraft/class_243;)V", 0),
                    "attackMotion"),                                                                     // setVelocity
            of(ENTITY_PLAYER, "method_75122", "(Lnet/minecraft/class_1297;FLnet/minecraft/class_243;)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, ENTITY_PLAYER, "method_5728", "(Z)V", 0), "attackSprint"),  // setSprinting
            of(PLAYER, "method_6007", "()V",                                                             // tickMovement
                    At.before(Opcodes.INVOKESPECIAL, CLIENT_PLAYER, "method_6007", "()V", 0), "livingUpdate").withSelf(),

            /* Freecam keeps the player sending and taking input while the camera is elsewhere. */
            of(ENTITY, "method_5872", "(DD)V", At.HEAD, "turn").withSelf().withArgs().cancelling(),     // changeLookDirection
            of(SODIUM_BLOCKS, "renderModel", "(Lnet/minecraft/class_1087;Lnet/minecraft/class_2680;"
                    + "Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;)V", At.HEAD, "xrayModel")
                    .withArgs().cancelling(),
            of(SODIUM_MESHING, "execute", SODIUM_EXECUTE,
                    At.redirect(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_3610", "method_15769", "()Z", -1), // isEmpty
                    "xrayFluidEmpty"),
            of(SODIUM_CONTEXT, "isFaceCulled", "(Lnet/minecraft/class_2350;)Z", At.RETURN, "xrayFaceCulled")
                    .withResult(),
            of(BLOCK_RENDERER, "method_3355", "(Lnet/minecraft/class_2680;Lnet/minecraft/class_2338;"  // renderBlock
                    + "Lnet/minecraft/class_1920;Lnet/minecraft/class_4587;Lnet/minecraft/class_4588;ZLjava/util/List;)V",
                    At.HEAD, "xrayBlock").withArgs().cancelling(),
            of(BLOCK_RENDERER, "method_3352", "(Lnet/minecraft/class_2338;Lnet/minecraft/class_1920;"  // renderFluid
                    + "Lnet/minecraft/class_4588;Lnet/minecraft/class_2680;Lnet/minecraft/class_3610;)V",
                    At.HEAD, "xrayVanillaFluid").withArgs().cancelling(),
            of(BLOCK, "method_9607", "(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;"          // shouldDrawSide
                    + "Lnet/minecraft/class_2350;)Z", At.RETURN, "xraySideDrawn").withResult(),
            of(LIGHTMAP, "method_3313", "(F)V",                                                        // update
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "method_6059", HAS_EFFECT, -1), "xrayNightVision"),
            of(GAME_RENDERER, "method_3174", "(Lnet/minecraft/class_1309;F)F",                        // getNightVisionStrength
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "method_6112",                         // getStatusEffect
                            "(Lnet/minecraft/class_6880;)Lnet/minecraft/class_1293;", 0), "xrayVisionEffect"),
            of(ENTITY, "method_5756", "(Lnet/minecraft/class_1657;)Z", At.RETURN, "invisibleTo")       // isInvisibleTo
                    .withSelf().withResult(),
            of(MINECRAFT, "method_27022", "(Lnet/minecraft/class_1297;)Z", At.RETURN, "outlined")       // hasOutline
                    .withArgs().withResult(),
            of(ENTITY_PLAYER, "bridge$getWornCosmetics", "()Ljava/util/List;", At.RETURN, "wornCosmetics")  // Lunar's
                    .withSelf().withResult(),
            of(CLIENT_PLAYER, "method_52814", "()Lnet/minecraft/class_8685;", At.RETURN, "capeSkin")      // getSkin
                    .withSelf().withResult(),
            of(PLAYER_LIKE, "method_74091", "(Lnet/minecraft/class_1664;)Z", At.RETURN, "capePart")     // isModelPartVisible
                    .withSelf().withArgs().withResult(),
            of(EFFECT_FOG, "method_42593", "(Lnet/minecraft/class_5636;Lnet/minecraft/class_1297;)Z",  // shouldApply
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "method_6059", HAS_EFFECT, -1), "effectFog"),
            of(GAME_RENDERER, "method_3188", "(Lnet/minecraft/class_9779;)V",                           // renderWorld
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "method_66279", EFFECT_FADE, -1), "nauseaFade"),
            of(HUD, "method_55798", "(Lnet/minecraft/class_332;Lnet/minecraft/class_9779;)V",         // renderMiscOverlays
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "method_66279", EFFECT_FADE, -1), "nauseaFade"),
            of(CAMERA, "method_19321", "(Lnet/minecraft/class_1937;Lnet/minecraft/class_1297;ZZF)V", At.HEAD,  // update
                    "cameraStart").withArgs(),
            of(CAMERA, "method_19321", "(Lnet/minecraft/class_1937;Lnet/minecraft/class_1297;ZZF)V", At.RETURN,
                    "cameraEnd"),
            of(PLAYER, "method_3136", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "method_3134", "()Z", 0), "motionViewEntity"),     // isCamera
            of(PLAYER, "method_6007", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "method_3134", "()Z", 0), "inputViewEntity"),

            /* InventoryManager's sorting owns the container while it runs. */
            of(PLAYER, "method_7346", "()V", At.HEAD, "closeScreen"),                                    // closeHandledScreen
            of(HANDLED_SCREEN, "method_25402", "(Lnet/minecraft/class_11909;Z)Z", At.HEAD, "containerClick")  // mouseClicked
                    .cancelling(),
            of(HANDLED_SCREEN, "method_25403", "(Lnet/minecraft/class_11909;DD)Z", At.HEAD, "containerDrag")  // mouseDragged
                    .cancelling(),
            of(HANDLED_SCREEN, "method_25406", "(Lnet/minecraft/class_11909;)Z", At.HEAD, "containerRelease") // mouseReleased
                    .cancelling(),
            of(HANDLED_SCREEN, "method_2383", "(Lnet/minecraft/class_1735;IILnet/minecraft/class_1713;)V",    // onMouseClick
                    At.HEAD, "slotClick").cancelling(),

            /* Nametags draws its own over players it hides the vanilla one of. */
            of(ENTITY_RENDERER, "method_3921", "(Lnet/minecraft/class_1297;D)Z", At.HEAD, "canRenderName")   // hasLabel
                    .withArgs().cancelling(),

            of(MINECRAFT, "method_1574", "()V", At.HEAD, "tickStart"),                               // tick
            of(MINECRAFT, "method_1574", "()V",
                    At.before(Opcodes.INVOKEVIRTUAL, GAME_RENDERER, "method_3190", "(F)V", 0), "targetRotation"),
            of(MINECRAFT, "method_1574", "()V", At.RETURN, "legacyTickEnd"),
            of(GAME_RENDERER, "method_3190", "(F)V", At.HEAD, "pickStart"),                          // updateCrosshairTarget
            of(GAME_RENDERER, "method_3190", "(F)V", At.RETURN, "pointedObject").withArgs(),
            of(GAME_RENDERER, "method_3190", "(F)V", At.RETURN, "pickEnd"),
            of(MINECRAFT, "method_1508", "()V", At.HEAD, "preInteract"),                             // handleInputEvents
            of(MINECRAFT, "method_1508", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, INVENTORY, "method_61496", "(I)V", -1), "setCurrentItem"),  // setSelectedSlot
            of(MINECRAFT, "method_1523", "(Z)V", At.HEAD, "gameLoop"),                               // render
            of(GAME_RENDERER, "method_3192", "(Lnet/minecraft/class_9779;Z)V", At.RETURN, "renderTickEnd")   // render
                    .withArgs(),
            of(MINECRAFT, "method_1536", "()Z", At.HEAD, "leftClick").cancelling(),                  // doAttack
            of(MINECRAFT, "method_1583", "()V", At.HEAD, "rightClick").cancelling(),                 // doItemUse
            of(MINECRAFT, "method_1481", "(Lnet/minecraft/class_638;)V", At.HEAD, "loadWorld").withArgs(),  // joinWorld
            of(MINECRAFT, "method_18096", "(Lnet/minecraft/class_437;ZZ)V", At.HEAD, "leaveWorld"),  // disconnect
            of(MINECRAFT, "method_1507", "(Lnet/minecraft/class_437;)V", At.HEAD, "openGui")         // setScreen
                    .withArgs().cancelling(),
            of(MOUSE, "method_1598", "(JDD)V",                                                       // onMouseScroll
                    At.redirect(Opcodes.INVOKEVIRTUAL, INVENTORY, "method_61496", "(I)V", -1), "scrollCurrentItem"),

            of(PLAYER, "method_5773", "()V",                                                         // tick
                    At.before(Opcodes.INVOKESPECIAL, CLIENT_PLAYER, "method_5773", "()V", 0), "preUpdate"),
            of(PLAYER, "method_5773", "()V", At.RETURN, "postUpdate").withSelf(),
            of(LIVING, "method_6043", "()V", At.HEAD, "jump").withSelf().cancelling(),               // jump
            of(ENTITY, "method_5724", "(FLnet/minecraft/class_243;)V", At.HEAD, "moveFlying")        // updateVelocity
                    .withSelf().withArgs().cancelling(),
            of(LIVING, "method_26318", "(Lnet/minecraft/class_243;F)Lnet/minecraft/class_243;",      // applyMovementInput
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "method_5784", MOVE, -1), "moveEntity"),
            of(LIVING, "method_76087", "(Lnet/minecraft/class_243;DZD)V",                            // travelInWater
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "method_5784", MOVE, -1), "moveEntityInWater"),
            of(LIVING, "method_76089", "(Lnet/minecraft/class_243;DZD)V",                            // travelInLava
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "method_5784", MOVE, -1), "moveEntityInLava"),
            of(KEYBOARD_INPUT, "method_3129", "()V", At.RETURN, "moveInput").withSelf(),            // tick

            of(CONNECTION, "method_52906", "(" + PACKET + "Lio/netty/channel/ChannelFutureListener;Z)V",   // send
                    At.HEAD, "sendPacket").withArgs().cancelling(),
            of(CONNECTION, "method_10770", "(Lio/netty/channel/ChannelHandlerContext;" + PACKET + ")V",     // channelRead0
                    At.HEAD, "receivePacket").withArgs().cancelling(),
            of(NET_HANDLER, "method_43596", "(Lnet/minecraft/class_7439;)V", At.HEAD, "chat")        // onGameMessage
                    .withArgs().cancelling(),
            of(COMMON_NET_HANDLER, "method_10839", "(Lnet/minecraft/class_9812;)V",                   // onDisconnected
                    At.HEAD, "disconnect"),
            of(NET_HANDLER, "method_11132", "(Lnet/minecraft/class_2743;)V", At.HEAD, "velocity")    // onEntityVelocityUpdate
                    .withArgs().cancelling(),
            of(NET_HANDLER, "method_11124", "(Lnet/minecraft/class_2664;)V", At.HEAD, "explosion")   // onExplosion
                    .withArgs(),
            of(NET_HANDLER, "method_11124", "(Lnet/minecraft/class_2664;)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2664", "comp_2884",            // playerKnockback
                            "()Ljava/util/Optional;", -1), "explosionPush"),
            of(WORLD, "method_53875", "(Lnet/minecraft/class_1297;)V", At.RETURN, "entityJoined")    // addEntity
                    .withSelf().withArgs(),
            of(ENTITY_PLAYER, "method_7324", "(Lnet/minecraft/class_1297;)V", At.HEAD, "playerAttack")  // attack
                    .withSelf().withArgs().cancelling(),
            of(WORLD_RENDERER, "method_22710",                                                       // render
                    "(Lnet/minecraft/class_9922;Lnet/minecraft/class_9779;ZLnet/minecraft/class_4184;"
                            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
                            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    At.RETURN, "renderWorldLast").withArgs());

    private LegacyHookTable() {}

    static List<Hook> over(List<Hook> base) {
        List<Hook> hooks = new ArrayList<>();
        for (Hook hook : base) {
            hooks.add(REPLACED.getOrDefault(hook.handler(), hook));
        }
        hooks.addAll(ADDED);
        return List.copyOf(hooks);
    }
}
