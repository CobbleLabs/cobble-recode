package cobble.v1_7;

import static cobble.boot.Hook.of;

import cobble.boot.Hook;
import cobble.boot.Hook.At;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * Where Lunar's 1.7.10 hooks go: 1.8.9's where 1.7.10 has the same methods,
 * and 1.7.10's own where it does not. The player's tick and movement packet
 * live on EntityClientPlayerMP, block actions take coordinates, and the frame
 * ends in resetSize.
 */
public final class HookTable {
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String CLIENT_PLAYER = "net/minecraft/client/entity/EntityClientPlayerMP";
    private static final String PLAYER = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String CONTROLLER = "net/minecraft/client/multiplayer/PlayerControllerMP";
    private static final String NETWORK = "net/minecraft/network/NetworkManager";
    private static final String RENDERER = "net/minecraft/client/renderer/EntityRenderer";
    private static final String LIVING_RENDERER = "net/minecraft/client/renderer/entity/RendererLivingEntity";
    private static final String RENDER_MANAGER = "net/minecraft/client/renderer/entity/RenderManager";
    /* Lunar draws the game's HUD and its own through this redirect. */
    private static final String LUNAR_RENDER_GUI = "redirect$*$lunar$renderGui";
    private static final String RENDER_GUI = "(Lnet/minecraft/client/gui/GuiIngame;FZII)V";
    private static final String BLOCK = "net/minecraft/block/Block";
    private static final String BLOCK_ACCESS = "net/minecraft/world/IBlockAccess";
    private static final String BLOCK_ACTION = "(IIII)V";
    private static final String IS_POTION_ACTIVE = "(Lnet/minecraft/potion/Potion;)Z";

    private static final Map<String, Hook> REPLACED = Map.ofEntries(
            Map.entry("frame", of(MINECRAFT, "resetSize", "()V", At.HEAD, "frame")),
            Map.entry("placeBlock", of(CONTROLLER, "onPlayerRightClick",
                    "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;"
                            + "Lnet/minecraft/item/ItemStack;IIIILnet/minecraft/util/Vec3;)Z",
                    At.HEAD, "placeBlock").cancelling()),
            Map.entry("clickBlock", of(CONTROLLER, "clickBlock", BLOCK_ACTION, At.HEAD, "clickBlock").cancelling()),
            Map.entry("clickHitDelay", of(CONTROLLER, "clickBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 0), "clickHitDelay").withSelf()),
            Map.entry("damageBlock", of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION, At.HEAD, "damageBlock")
                    .withSelf().cancelling()),
            Map.entry("creativeHitDelay", of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 1), "creativeHitDelay").withSelf()),
            Map.entry("breakHitDelay", of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION,
                    At.after(Opcodes.PUTFIELD, CONTROLLER, "blockHitDelay", "I", 2), "breakHitDelay").withSelf()),
            Map.entry("blockDamaged", of(CONTROLLER, "onPlayerDamageBlock", BLOCK_ACTION, At.RETURN, "blockDamaged")
                    .withSelf()),
            Map.entry("preUpdate", of(CLIENT_PLAYER, "onUpdate", "()V",
                    At.before(Opcodes.INVOKESPECIAL, PLAYER, "onUpdate", "()V", 0), "preUpdate")),
            Map.entry("postUpdate", of(CLIENT_PLAYER, "onUpdate", "()V", At.RETURN, "postUpdate").withSelf()),
            Map.entry("preMotion", of(CLIENT_PLAYER, "sendMotionUpdates", "()V", At.HEAD, "preMotion").withSelf()),
            Map.entry("postMotion", of(CLIENT_PLAYER, "sendMotionUpdates", "()V", At.RETURN, "postMotion").withSelf()),
            Map.entry("sendPacket", of(NETWORK, "scheduleOutboundPacket",
                    "(Lnet/minecraft/network/Packet;[Lio/netty/util/concurrent/GenericFutureListener;)V",
                    At.HEAD, "sendPacket").withArgs().cancelling()),
            Map.entry("renderName", of(LIVING_RENDERER, "passSpecialRender",
                    "(Lnet/minecraft/entity/EntityLivingBase;DDD)V", At.HEAD, "renderName").withArgs().cancelling()),
            Map.entry("renderInfoCached", of(RENDER_MANAGER, "cacheActiveRenderInfo",
                    "(Lnet/minecraft/world/World;Lnet/minecraft/client/renderer/texture/TextureManager;"
                            + "Lnet/minecraft/client/gui/FontRenderer;Lnet/minecraft/entity/EntityLivingBase;"
                            + "Lnet/minecraft/entity/Entity;Lnet/minecraft/client/settings/GameSettings;F)V",
                    At.RETURN, "renderInfoCached").withSelf()),
            // getMouseOver has no profiler section on 1.7.10; its one return is just after the pick.
            Map.entry("pointedObject", of(RENDERER, "getMouseOver", "(F)V", At.RETURN, "pointedObject").withArgs()),
            Map.entry("nauseaPotion", of(RENDERER, "setupCameraTransform", "(FI)V",
                    At.redirect(Opcodes.INVOKEVIRTUAL, CLIENT_PLAYER, "isPotionActive", IS_POTION_ACTIVE, -1),
                    "nauseaPotion")),
            Map.entry("mouseTurn", of(RENDERER, "updateCameraAndRender", "(F)V",
                    At.redirect(Opcodes.GETFIELD, MINECRAFT, "inGameHasFocus", "Z", 0), "mouseTurn")),
            Map.entry("hudStart", of(RENDERER, "updateCameraAndRender", "(F)V",
                    At.before(Opcodes.INVOKEVIRTUAL, RENDERER, LUNAR_RENDER_GUI, RENDER_GUI, 0), "hudStart")),
            Map.entry("renderTickEnd", of(RENDERER, "updateCameraAndRender", "(F)V",
                    At.after(Opcodes.INVOKEVIRTUAL, RENDERER, LUNAR_RENDER_GUI, RENDER_GUI, 0), "renderTickEnd")
                    .withArgs()),
            // The hand is drawn behind the second camera zoom check.
            Map.entry("renderWorldLast", of(RENDERER, "renderWorld", "(FJ)V",
                    At.before(Opcodes.GETFIELD, RENDERER, "cameraZoom", "D", 1), "renderWorldLast").withArgs()),
            Map.entry("renderBlock", of("net/minecraft/client/renderer/RenderBlocks", "renderBlockByRenderType",
                    "(Lnet/minecraft/block/Block;III)Z", At.HEAD, "renderBlock").withArgs().cancelling()),
            Map.entry("sideOccluded", of(BLOCK, "shouldSideBeRendered", "(L" + BLOCK_ACCESS + ";IIII)Z",
                    At.redirect(Opcodes.INVOKEVIRTUAL, BLOCK, "isOpaqueCube", "()Z", -1), "sideOccluded")),
            Map.entry("blockLight", of(BLOCK, "getMixedBrightnessForBlock", "(L" + BLOCK_ACCESS + ";III)I",
                    At.redirect(Opcodes.INVOKEINTERFACE, BLOCK_ACCESS, "getLightBrightnessForSkyBlocks", "(IIII)I", -1),
                    "blockLight")),
            // 1.7.10 has no skin layers; it asks whether there is a cape at all before it draws one.
            Map.entry("partWorn", of("net/minecraft/client/entity/AbstractClientPlayer", "hasCape", "()Z", At.RETURN,
                    "capeWorn").withSelf().withResult()));

    /* 1.7.10's movement packet does not ask whose view it is. */
    private static final Set<String> DROPPED = Set.of("motionViewEntity", "inputViewEntity");

    public static final List<Hook> ALL = build();

    private HookTable() {}

    private static List<Hook> build() {
        List<Hook> hooks = new ArrayList<>();
        for (Hook hook : cobble.hook.HookTable.ALL) {
            if (!DROPPED.contains(hook.handler())) {
                hooks.add(REPLACED.getOrDefault(hook.handler(), hook));
            }
        }
        return List.copyOf(hooks);
    }
}
