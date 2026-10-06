package cobble.hook;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import static cobble.boot.Hook.of;
import java.util.List;
import org.objectweb.asm.Opcodes;

/** Where 1.8.9's hooks go, under the MCP names Lunar runs it with; the handlers are {@link Hooks}'. */
public final class HookTable {
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String PLAYER = "net/minecraft/client/entity/EntityPlayerSP";
    static final String CLIENT_PLAYER = "net/minecraft/client/entity/AbstractClientPlayer";
    static final String ENTITY = "net/minecraft/entity/Entity";
    static final String LIVING = "net/minecraft/entity/EntityLivingBase";
    static final String ENTITY_PLAYER = "net/minecraft/entity/player/EntityPlayer";
    static final String INVENTORY = "net/minecraft/entity/player/InventoryPlayer";
    static final String SETTINGS = "net/minecraft/client/settings/GameSettings";
    static final String CONTROLLER = "net/minecraft/client/multiplayer/PlayerControllerMP";
    static final String MOVE_INPUT = "net/minecraft/util/MovementInputFromOptions";
    static final String NETWORK = "net/minecraft/network/NetworkManager";
    static final String NET_HANDLER = "net/minecraft/client/network/NetHandlerPlayClient";
    static final String WORLD = "net/minecraft/world/World";
    static final String RENDERER = "net/minecraft/client/renderer/EntityRenderer";
    static final String LIVING_RENDERER = "net/minecraft/client/renderer/entity/RendererLivingEntity";
    static final String RENDER_PLAYER = "net/minecraft/client/renderer/entity/RenderPlayer";
    static final String RENDER_MANAGER = "net/minecraft/client/renderer/entity/RenderManager";
    static final String GUI_CONTAINER = "net/minecraft/client/gui/inventory/GuiContainer";
    static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";
    static final String BLOCK = "net/minecraft/block/Block";
    static final String BLOCK_ACCESS = "net/minecraft/world/IBlockAccess";
    static final String BLOCK_DISPATCHER = "net/minecraft/client/renderer/BlockRendererDispatcher";

    private static final String BLOCK_ACTION = "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z";
    private static final String IS_POTION_ACTIVE = "(Lnet/minecraft/potion/Potion;)Z";
    private static final String ATTACK = "(Lnet/minecraft/entity/Entity;)V";
    private static final String RENDER_PLAYER_ARGS = "(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V";
    private static final String CAN_RENDER_NAME = "(Lnet/minecraft/entity/EntityLivingBase;)Z";
    /* Lunar draws the game's HUD and its own through this wrapper. */
    private static final String LUNAR_RENDER_GUI = "(Lnet/minecraft/client/gui/GuiIngame;F"
            + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V";

    /**
     * Everything the client needs from the game's control flow. The tick hook
     * sits after the crosshair ray and before input handling, so modules see a
     * fresh objectMouseOver and their clicks go out ahead of the movement
     * packet, where vanilla sends its own. The frame hook runs after the world
     * and the game's HUD are drawn, right before the buffer swap. The motion
     * pair brackets the movement packet for silent rotations. The rest raise
     * the module events.
     */
    public static final List<Hook> ALL = List.of(
            of(MINECRAFT, "runTick", "()V", At.HEAD, "tickStart"),
            of(MINECRAFT, "runTick", "()V",
                    At.before(Opcodes.INVOKEVIRTUAL, RENDERER, "getMouseOver", "(F)V", 0), "targetRotation"),
            of(MINECRAFT, "runTick", "()V",
                    At.after(Opcodes.INVOKEVIRTUAL, RENDERER, "getMouseOver", "(F)V", 0), "tick"),
            of(MINECRAFT, "runTick", "()V",
                    At.after(Opcodes.INVOKEVIRTUAL, CONTROLLER, "updateController", "()V", 0), "controllerUpdated"),
            of(MINECRAFT, "runTick", "()V",
                    At.before(Opcodes.GETFIELD, SETTINGS, "chatVisibility",
                            "Lnet/minecraft/entity/player/EntityPlayer$EnumChatVisibility;", 0),
                    "preInteract"),
            of(MINECRAFT, "runTick", "()V",
                    At.after(Opcodes.INVOKESPECIAL, MINECRAFT, "sendClickBlockToController", "(Z)V", 0),
                    "blockClickSent"),
            of(MINECRAFT, "runTick", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, INVENTORY, "changeCurrentItem", "(I)V", 0),
                    "changeCurrentItem"),
            of(MINECRAFT, "runTick", "()V",
                    At.redirect(Opcodes.PUTFIELD, SETTINGS, "thirdPersonView", "I", -1), "setThirdPersonView"),
            of(MINECRAFT, "runTick", "()V",
                    At.redirect(Opcodes.PUTFIELD, INVENTORY, "currentItem", "I", 0), "setCurrentItem"),
            of(MINECRAFT, "runTick", "()V", At.RETURN, "tickEnd"),
            of(MINECRAFT, "runGameLoop", "()V", At.HEAD, "gameLoop"),
            of(MINECRAFT, "updateDisplay", "()V", At.HEAD, "frame"),
            of(MINECRAFT, "clickMouse", "()V", At.HEAD, "leftClick").cancelling(),
            of(MINECRAFT, "rightClickMouse", "()V", At.HEAD, "rightClick").cancelling(),
            of(MINECRAFT, "loadWorld", "(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
                    At.HEAD, "loadWorld").withArgs(),
            of(MINECRAFT, "displayGuiScreen", "(Lnet/minecraft/client/gui/GuiScreen;)V", At.HEAD, "openGui")
                    .withArgs().cancelling(),

            of(CONTROLLER, "sendUseItem",
                    "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;"
                            + "Lnet/minecraft/item/ItemStack;)Z",
                    At.HEAD, "useItem").withArgs().cancelling(),
            of(CONTROLLER, "onPlayerRightClick",
                    "(Lnet/minecraft/client/entity/EntityPlayerSP;Lnet/minecraft/client/multiplayer/WorldClient;"
                            + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/BlockPos;"
                            + "Lnet/minecraft/util/EnumFacing;Lnet/minecraft/util/Vec3;)Z",
                    At.HEAD, "placeBlock").cancelling(),
            of(CONTROLLER, "attackEntity", "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/entity/Entity;)V",
                    At.HEAD, "attackEntity").withArgs().cancelling(),
            of(CONTROLLER, "clickBlock", BLOCK_ACTION, At.HEAD, "clickBlock").cancelling(),
            of(CONTROLLER, "clickBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 0), "clickHitDelay").withSelf(),
            of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION, At.HEAD, "damageBlock").withSelf().cancelling(),
            of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 1), "creativeHitDelay").withSelf(),
            of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 2), "breakHitDelay").withSelf(),
            of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION, At.RETURN, "blockDamaged").withSelf(),

            of(ENTITY, "setAngles", "(FF)V", At.HEAD, "turn").withSelf().withArgs().cancelling(),
            of(ENTITY, "moveFlying", "(FFF)V", At.HEAD, "moveFlying").withSelf().withArgs().cancelling(),
            of(LIVING, "jump", "()V", At.HEAD, "jump").withSelf().cancelling(),
            of(LIVING, "moveEntityWithHeading", "(FF)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "moveEntity", "(DDD)V", -1), "moveEntity"),
            of(ENTITY, "getCollisionBorderSize", "()F", At.RETURN, "collisionBorder").withSelf().withResult(),

            of(PLAYER, "onUpdate", "()V",
                    At.before(Opcodes.INVOKESPECIAL, CLIENT_PLAYER, "onUpdate", "()V", 0), "preUpdate"),
            of(PLAYER, "onUpdate", "()V", At.RETURN, "postUpdate").withSelf(),
            of(PLAYER, "onUpdateWalkingPlayer", "()V", At.HEAD, "preMotion").withSelf(),
            of(PLAYER, "onUpdateWalkingPlayer", "()V", At.RETURN, "postMotion").withSelf(),
            of(PLAYER, "onUpdateWalkingPlayer", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "isCurrentViewEntity", "()Z", 0), "motionViewEntity"),
            of(PLAYER, "updateEntityActionState", "()V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "isCurrentViewEntity", "()Z", 0), "inputViewEntity"),
            of(PLAYER, "onLivingUpdate", "()V",
                    At.before(Opcodes.INVOKESPECIAL, CLIENT_PLAYER, "onLivingUpdate", "()V", 0), "livingUpdate")
                    .withSelf(),
            of(PLAYER, "closeScreen", "()V", At.HEAD, "closeScreen"),

            of(MOVE_INPUT, "updatePlayerMoveState", "()V",
                    At.after(Opcodes.PUTFIELD, MOVE_INPUT, "sneak", "Z", 0), "moveInput").withSelf(),
            of(MOVE_INPUT, "updatePlayerMoveState", "()V", At.RETURN, "moveInputDone"),

            of(NETWORK, "sendPacket", "(Lnet/minecraft/network/Packet;)V", At.HEAD, "sendPacket")
                    .withArgs().cancelling(),
            of(NETWORK, "channelRead0", "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V",
                    At.HEAD, "receivePacket").withArgs().cancelling(),
            of(NET_HANDLER, "handleChat", "(Lnet/minecraft/network/play/server/S02PacketChat;)V", At.HEAD, "chat")
                    .withArgs().cancelling(),
            of(NET_HANDLER, "handleDisconnect", "(Lnet/minecraft/network/play/server/S40PacketDisconnect;)V",
                    At.HEAD, "disconnect"),
            of(NET_HANDLER, "handleEntityVelocity", "(Lnet/minecraft/network/play/server/S12PacketEntityVelocity;)V",
                    At.HEAD, "velocity").withArgs().cancelling(),
            of(NET_HANDLER, "handleExplosion", "(Lnet/minecraft/network/play/server/S27PacketExplosion;)V",
                    At.HEAD, "explosion").withArgs(),
            of(WORLD, "spawnEntityInWorld", "(Lnet/minecraft/entity/Entity;)Z", At.RETURN, "entityJoined")
                    .withSelf().withArgs(),

            of(ENTITY_PLAYER, "attackTargetEntityWithCurrentItem", ATTACK, At.HEAD, "playerAttack")
                    .withSelf().withArgs().cancelling(),
            of(ENTITY_PLAYER, "attackTargetEntityWithCurrentItem", ATTACK,
                    At.before(Opcodes.INVOKEVIRTUAL, ENTITY, "addVelocity", "(DDD)V", 0), "knockback").withSelf(),
            of(ENTITY_PLAYER, "attackTargetEntityWithCurrentItem", ATTACK,
                    At.redirect(Opcodes.INVOKEVIRTUAL, ENTITY_PLAYER, "setSprinting", "(Z)V", 0), "attackSprint"),

            of(LIVING_RENDERER, "doRender", "(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", At.HEAD,
                    "renderLiving").withArgs(),
            of(LIVING_RENDERER, "renderModel", "(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "isInvisible", "()Z", 0), "modelInvisible"),
            of(LIVING_RENDERER, "canRenderName", CAN_RENDER_NAME, At.HEAD, "canRenderName").withArgs().cancelling(),
            of(LIVING_RENDERER, "canRenderName", CAN_RENDER_NAME,
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "isInvisibleToPlayer",
                            "(Lnet/minecraft/entity/player/EntityPlayer;)Z", 0),
                    "nameInvisible"),
            of(LIVING_RENDERER, "renderName", "(Lnet/minecraft/entity/EntityLivingBase;DDD)V", At.HEAD, "renderName")
                    .withArgs().cancelling(),
            of(RENDER_PLAYER, "doRender", RENDER_PLAYER_ARGS, At.HEAD, "renderPlayer").withSelf().withArgs(),
            of(RENDER_PLAYER, "doRender", RENDER_PLAYER_ARGS, At.RETURN, "playerRendered").withSelf().withArgs(),
            of(RENDER_MANAGER, "cacheActiveRenderInfo",
                    "(Lnet/minecraft/world/World;Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/entity/Entity;"
                            + "Lnet/minecraft/entity/Entity;Lnet/minecraft/client/settings/GameSettings;F)V",
                    At.RETURN, "renderInfoCached").withSelf(),
            /* OptiFine, which Lunar ships, answers with null while its capes are
             * off and with its own downloaded cape when there is one, before it
             * ever asks the player info, so the cape replaces whatever comes back. */
            of(CLIENT_PLAYER, "getLocationCape", "()Lnet/minecraft/util/ResourceLocation;", At.RETURN,
                    "capeTexture").withSelf().withResult(),
            of(ENTITY_PLAYER, "isWearing", "(Lnet/minecraft/entity/player/EnumPlayerModelParts;)Z", At.RETURN,
                    "partWorn").withSelf().withArgs().withResult(),

            of(GUI_CONTAINER, "mouseClicked", "(III)V", At.HEAD, "containerClick").cancelling(),
            of(GUI_CONTAINER, "mouseClickMove", "(IIIJ)V", At.HEAD, "containerDrag").cancelling(),
            of(GUI_CONTAINER, "mouseReleased", "(III)V", At.HEAD, "containerRelease").cancelling(),
            of(GUI_CONTAINER, "handleMouseClick", "(Lnet/minecraft/inventory/Slot;III)V", At.HEAD, "slotClick")
                    .withArgs().cancelling(),
            of(GUI_SCREEN, "handleMouseInput", "()V", At.HEAD, "screenMouseInput"),

            of(RENDERER, "getMouseOver", "(F)V", At.HEAD, "pickStart"),
            of(RENDERER, "getMouseOver", "(F)V",
                    At.before(Opcodes.INVOKEVIRTUAL, "net/minecraft/profiler/Profiler", "endSection", "()V", 0),
                    "pointedObject").withArgs(),
            of(RENDERER, "getMouseOver", "(F)V", At.RETURN, "pickEnd"),
            of(RENDERER, "orientCamera", "(F)V", At.HEAD, "cameraStart"),
            of(RENDERER, "orientCamera", "(F)V", At.RETURN, "cameraEnd"),
            of(RENDERER, "orientCamera", "(F)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/Vec3", "distanceTo",
                            "(Lnet/minecraft/util/Vec3;)D", 0),
                    "cameraClip"),
            of(RENDERER, "setupFog", "(IF)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "isPotionActive", IS_POTION_ACTIVE, -1), "fogPotion"),
            of(RENDERER, "updateFogColor", "(F)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, LIVING, "isPotionActive", IS_POTION_ACTIVE, -1),
                    "fogColorPotion"),
            of(RENDERER, "setupCameraTransform", "(FI)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, PLAYER, "isPotionActive", IS_POTION_ACTIVE, -1), "nauseaPotion"),
            of(RENDERER, "updateCameraAndRender", "(FJ)V",
                    At.redirect(Opcodes.GETFIELD, MINECRAFT, "inGameHasFocus", "Z", 1), "mouseTurn"),
            of(RENDERER, "updateCameraAndRender", "(FJ)V",
                    At.before(Opcodes.INVOKEVIRTUAL, RENDERER, "wrapOperation$*$lunar$renderGui", LUNAR_RENDER_GUI, 0),
                    "hudStart"),
            of(RENDERER, "updateCameraAndRender", "(FJ)V",
                    At.after(Opcodes.INVOKEVIRTUAL, RENDERER, "wrapOperation$*$lunar$renderGui", LUNAR_RENDER_GUI, 0),
                    "renderTickEnd").withArgs(),
            of(RENDERER, "renderWorldPass", "(IFJ)V",
                    At.before(Opcodes.GETFIELD, RENDERER, "renderHand", "Z", 0), "renderWorldLast").withArgs(),

            of(BLOCK_DISPATCHER, "renderBlock",
                    "(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockPos;L" + BLOCK_ACCESS
                            + ";Lnet/minecraft/client/renderer/WorldRenderer;)Z",
                    At.HEAD, "renderBlock").withArgs().cancelling(),
            of(BLOCK, "shouldSideBeRendered",
                    "(L" + BLOCK_ACCESS + ";Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z",
                    At.redirect(Opcodes.INVOKEVIRTUAL, BLOCK, "isOpaqueCube", "()Z", -1), "sideOccluded"),
            of(BLOCK, "getMixedBrightnessForBlock", "(L" + BLOCK_ACCESS + ";Lnet/minecraft/util/BlockPos;)I",
                    At.redirect(Opcodes.INVOKEINTERFACE, BLOCK_ACCESS, "getCombinedLight",
                            "(Lnet/minecraft/util/BlockPos;I)I", -1),
                    "blockLight"),
            of(MINECRAFT, "isAmbientOcclusionEnabled", "()Z", At.HEAD, "ambientOcclusion").cancelling());

    private HookTable() {}
}
