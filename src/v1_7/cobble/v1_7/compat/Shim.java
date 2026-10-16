package cobble.v1_7.compat;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.RegistryNamespaced;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.lwjgl.opengl.GL11;

/**
 * What a 1.8.9 call means on 1.7.10, for the calls 1.7.10 has no plain rename
 * of. tools/Retarget.java points the legacy code here through
 * src/v1_7/retarget.rules; each helper takes the receiver of the call it
 * replaces as its first argument.
 */
public final class Shim {
    private Shim() {}

    // ------------------------------------------------------------- client ----

    /* 1.8.9 sizes the GUI from the window; 1.7.10 has to be told the window's size. */
    public static ScaledResolution scaledResolution(Minecraft mc) {
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    public static int debugFps() {
        return Minecraft.debugFPS;
    }

    public static void setRenderViewEntity(Minecraft mc, Entity entity) {
        mc.renderViewEntity = (EntityLivingBase) entity;
    }

    public static BlockRendererDispatcher blockRendererDispatcher(Minecraft mc) {
        return BlockRendererDispatcher.INSTANCE;
    }

    /* 1.7.10 lays a signed book's open page out as plain text; these are its lines as 1.8.9's screen keeps them. */
    public static List<IChatComponent> bookLines(GuiScreenBook screen) {
        if (screen.bookPages == null || screen.currPage >= screen.bookPages.tagCount()) {
            return null;
        }
        List<IChatComponent> lines = new ArrayList<>();
        for (Object line : Minecraft.getMinecraft().fontRendererObj
                .listFormattedStringToWidth(screen.bookPages.getStringTagAt(screen.currPage), 116)) {
            lines.add(new ChatComponentText((String) line));
        }
        return lines;
    }

    // ------------------------------------------------------------- player ----

    public static NetHandlerPlayClient sendQueue(EntityPlayerSP player) {
        return ((EntityClientPlayerMP) player).sendQueue;
    }

    public static void sendChatMessage(EntityPlayerSP player, String message) {
        ((EntityClientPlayerMP) player).sendChatMessage(message);
    }

    public static boolean isCurrentViewEntity(EntityPlayerSP player) {
        return Minecraft.getMinecraft().renderViewEntity == player;
    }

    /* There is no spectator mode before 1.8. */
    public static boolean isSpectator(Entity entity) {
        return false;
    }

    public static boolean isInLava(Entity entity) {
        return entity.handleLavaMovement();
    }

    /*
     * The player model as the renderer draws it. Vanilla keeps it twice, as
     * mainModel and modelBipedMain; CheatBreaker builds since 2026-10-13 keep
     * only mainModel, holding a model of their own that extends ModelBiped.
     */
    public static ModelBiped playerModel(RenderPlayer renderer) {
        return (ModelBiped) renderer.mainModel;
    }

    /** The eyes at {@code partialTicks}, as 1.8.9 puts them. */
    public static Vec3 positionEyes(Entity entity, float partialTicks) {
        double y = EntityShim.posY(entity);
        float eyes = EntityShim.getEyeHeight(entity);
        if (partialTicks == 1.0f) {
            return Vec3.createVectorHelper(entity.posX, y + eyes, entity.posZ);
        }
        double prevY = EntityShim.prevPosY(entity);
        return Vec3.createVectorHelper(entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks,
                prevY + (y - prevY) * partialTicks + eyes,
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks);
    }

    /* 1.8.9's from.subtract(vec) is from - vec; 1.7.10's is vec - from. */
    public static Vec3 subtract(Vec3 from, Vec3 vec) {
        return Vec3.createVectorHelper(from.xCoord - vec.xCoord, from.yCoord - vec.yCoord, from.zCoord - vec.zCoord);
    }

    /* 1.7.10's offset moves the box itself, which is often an entity's own. */
    public static AxisAlignedBB offset(AxisAlignedBB box, double x, double y, double z) {
        return AxisAlignedBB.getBoundingBox(box.minX + x, box.minY + y, box.minZ + z, box.maxX + x, box.maxY + y,
                box.maxZ + z);
    }

    /* 1.7.10 steps the trace by moving the start vector it is given; the caller keeps using its own. */
    public static MovingObjectPosition rayTraceBlocks(World world, Vec3 start, Vec3 end, boolean liquids,
                                                      boolean ignoreNoBox, boolean lastUncollidable) {
        return world.rayTraceBlocks(Vec3.createVectorHelper(start.xCoord, start.yCoord, start.zCoord), end, liquids,
                ignoreNoBox, lastUncollidable);
    }

    /* 1.8.9's: from the eyes along the look, against blocks only. */
    public static MovingObjectPosition rayTrace(Entity viewer, double reach, float partialTicks) {
        Vec3 eyes = positionEyes(viewer, partialTicks);
        Vec3 look = look(viewer, partialTicks);
        return viewer.worldObj.rayTraceBlocks(eyes,
                eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach), false, false, true);
    }

    /* 1.8.9 keeps every entity's look on Entity; 1.7.10 only has it on living ones. */
    public static Vec3 look(Entity entity, float partialTicks) {
        if (entity instanceof EntityLivingBase living) {
            return living.getLook(partialTicks);
        }
        return entity.getLookVec();
    }

    public static BlockPos position(Entity entity) {
        return new BlockPos(entity);
    }

    public static double distanceSq(Entity entity, BlockPos pos) {
        double x = pos.getX() - entity.posX;
        double y = pos.getY() - EntityShim.posY(entity);
        double z = pos.getZ() - entity.posZ;
        return x * x + y * y + z * z;
    }

    public static IChatComponent displayName(Entity entity) {
        return new ChatComponentText(entity.getCommandSenderName());
    }

    public static float jumpUpwardsMotion(EntityLivingBase entity) {
        return 0.42f;
    }

    /* 1.8.9 counts the armour from the feet up, 1.7.10's equipment slots from the hand. */
    public static ItemStack currentArmor(EntityLivingBase entity, int slot) {
        return entity.getEquipmentInSlot(slot + 1);
    }

    // ------------------------------------------------------------ the tab ----

    public static NetworkPlayerInfo playerInfo(NetHandlerPlayClient handler, UUID id) {
        World world = Minecraft.getMinecraft().theWorld;
        EntityPlayer player = world != null ? world.getPlayerEntityByUUID(id) : null;
        return player != null ? playerInfo(handler, player) : null;
    }

    public static NetworkPlayerInfo playerInfo(AbstractClientPlayer player) {
        return playerInfo(Minecraft.getMinecraft().getNetHandler(), player);
    }

    @SuppressWarnings("unchecked")
    private static NetworkPlayerInfo playerInfo(NetHandlerPlayClient handler, EntityPlayer player) {
        GuiPlayerInfo entry = handler != null
                ? (GuiPlayerInfo) ((Map<String, GuiPlayerInfo>) handler.playerInfoMap).get(player.getCommandSenderName())
                : null;
        if (entry == null) {
            return null;
        }
        return new NetworkPlayerInfo(player.getGameProfile(), entry.responseTime,
                player instanceof AbstractClientPlayer client ? client.getLocationSkin() : null,
                player.worldObj.getScoreboard().getPlayersTeam(entry.name));
    }

    @SuppressWarnings("unchecked")
    public static Collection<NetworkPlayerInfo> playerInfoMap(NetHandlerPlayClient handler) {
        World world = Minecraft.getMinecraft().theWorld;
        List<NetworkPlayerInfo> entries = new ArrayList<>();
        for (GuiPlayerInfo entry : (List<GuiPlayerInfo>) handler.playerInfoList) {
            EntityPlayer player = world != null ? world.getPlayerEntityByName(entry.name) : null;
            GameProfile profile = player != null ? player.getGameProfile() : new GameProfile(null, entry.name);
            entries.add(new NetworkPlayerInfo(profile, entry.responseTime,
                    player instanceof AbstractClientPlayer client ? client.getLocationSkin() : null,
                    world != null ? world.getScoreboard().getPlayersTeam(entry.name) : null));
        }
        return entries;
    }

    // -------------------------------------------------------------- world ----

    public static IBlockState blockState(IBlockAccess world, BlockPos pos) {
        return new BlockState(world.getBlock(pos.getX(), pos.getY(), pos.getZ()),
                world.getBlockMetadata(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static boolean isBlockLoaded(World world, BlockPos pos) {
        return world.blockExists(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int combinedLight(IBlockAccess world, BlockPos pos, int minimum) {
        return world.getLightBrightnessForSkyBlocks(pos.getX(), pos.getY(), pos.getZ(), minimum);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List entitiesExcluding(World world, Entity excluded, AxisAlignedBB box,
                                         com.google.common.base.Predicate predicate) {
        List<Entity> found = new ArrayList<>((List<Entity>) world.getEntitiesWithinAABBExcludingEntity(excluded, box));
        found.removeIf(entity -> !predicate.apply(entity));
        return found;
    }

    // ------------------------------------------------------------- blocks ----

    public static IBlockState defaultState(Block block) {
        return new BlockState(block, 0);
    }

    public static int metaFromState(Block block, IBlockState state) {
        return state.metadata();
    }

    public static int stateId(IBlockState state) {
        return Block.getIdFromBlock(state.getBlock()) | state.metadata() << 12;
    }

    public static MapColor mapColor(Block block, IBlockState state) {
        return block.getMapColor(state.metadata());
    }

    public static boolean canCollideCheck(Block block, IBlockState state, boolean hitIfLiquid) {
        return block.canStopRayTrace(state.metadata(), hitIfLiquid);
    }

    public static float blockHardness(Block block, World world, BlockPos pos) {
        return block.getBlockHardness(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean isReplaceable(Block block, World world, BlockPos pos) {
        return block.getMaterial().isReplaceable();
    }

    public static MovingObjectPosition collisionRayTrace(Block block, World world, BlockPos pos, Vec3 from, Vec3 to) {
        return block.collisionRayTrace(world, pos.getX(), pos.getY(), pos.getZ(), from, to);
    }

    public static AxisAlignedBB selectedBoundingBox(Block block, World world, BlockPos pos) {
        return block.getSelectedBoundingBoxFromPool(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static AxisAlignedBB collisionBoundingBox(Block block, World world, BlockPos pos, IBlockState state) {
        return block.getCollisionBoundingBoxFromPool(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static void setBlockBoundsBasedOnState(Block block, IBlockAccess world, BlockPos pos) {
        block.setBlockBoundsBasedOnState(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static int damageValue(Block block, World world, BlockPos pos) {
        return block.getDamageValue(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static Item item(Block block, World world, BlockPos pos) {
        return block.getItem(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static Block itemBlock(ItemBlock item) {
        return item.blockInstance;
    }

    public static boolean canPlaceBlockOnSide(ItemBlock item, World world, BlockPos pos, EnumFacing side,
                                              EntityPlayer player, ItemStack stack) {
        return item.func_150936_a(world, pos.getX(), pos.getY(), pos.getZ(), side.ordinal(), player, stack);
    }

    /* Blocks and items 1.7.10 does not have: nothing is ever one. */
    public static Block absentBlock() {
        return null;
    }

    public static Item absentItem() {
        return null;
    }

    // ------------------------------------------------------------ registry ----

    /* 1.7.10 names by string; 1.8.9 hands back a ResourceLocation, which the client asks for its path. */
    public static Object nameForObject(RegistryNamespaced registry, Object object) {
        String name = registry.getNameForObject(object);
        return name != null ? new ResourceLocation(name) : null;
    }

    public static Item itemByNameOrId(String name) {
        Item byName = (Item) Item.itemRegistry.getObject(name.indexOf(':') >= 0 ? name : "minecraft:" + name);
        if (byName != null) {
            return byName;
        }
        try {
            return Item.getItemById(Integer.parseInt(name));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --------------------------------------------------------- interaction ----

    public static BlockPos blockPos(MovingObjectPosition hit) {
        return new BlockPos(hit.blockX, hit.blockY, hit.blockZ);
    }

    public static EnumFacing sideHit(MovingObjectPosition hit) {
        return hit.sideHit >= 0 ? EnumFacing.getFront(hit.sideHit) : null;
    }

    public static MovingObjectPosition blockHit(Vec3 point, EnumFacing side, BlockPos pos) {
        return new MovingObjectPosition(pos.getX(), pos.getY(), pos.getZ(), side.ordinal(), point);
    }

    public static MovingObjectPosition blockHit(MovingObjectPosition.MovingObjectType type, Vec3 point,
                                                EnumFacing side, BlockPos pos) {
        MovingObjectPosition hit = blockHit(point, side, pos);
        hit.typeOfHit = type;
        return hit;
    }

    public static MovingObjectPosition miss(Vec3 point, EnumFacing side) {
        MovingObjectPosition hit = new MovingObjectPosition(0, 0, 0, side.ordinal(), point, false);
        hit.typeOfHit = MovingObjectPosition.MovingObjectType.MISS;
        return hit;
    }

    public static BlockPos currentBlock(PlayerControllerMP controller) {
        return new BlockPos(controller.currentBlockX, controller.currentBlockY, controller.currentblockZ);
    }

    public static WorldSettings.GameType currentGameType(PlayerControllerMP controller) {
        return controller.currentGameType;
    }

    public static boolean clickBlock(PlayerControllerMP controller, BlockPos pos, EnumFacing side) {
        controller.clickBlock(pos.getX(), pos.getY(), pos.getZ(), side.ordinal());
        return true;
    }

    public static boolean damageBlock(PlayerControllerMP controller, BlockPos pos, EnumFacing side) {
        controller.onPlayerDamageBlock(pos.getX(), pos.getY(), pos.getZ(), side.ordinal());
        return true;
    }

    public static boolean rightClick(PlayerControllerMP controller, EntityPlayerSP player,
                                     net.minecraft.client.multiplayer.WorldClient world, ItemStack stack, BlockPos pos,
                                     EnumFacing side, Vec3 hit) {
        return controller.onPlayerRightClick(player, world, stack, pos.getX(), pos.getY(), pos.getZ(), side.ordinal(),
                hit);
    }

    // ------------------------------------------------------------ packets ----

    public static C08PacketPlayerBlockPlacement placement(ItemStack stack) {
        return new C08PacketPlayerBlockPlacement(-1, -1, -1, 255, stack, 0.0f, 0.0f, 0.0f);
    }

    public static C08PacketPlayerBlockPlacement placement(BlockPos pos, int side, ItemStack stack, float x, float y,
                                                          float z) {
        return new C08PacketPlayerBlockPlacement(pos.getX(), pos.getY(), pos.getZ(), side, stack, x, y, z);
    }

    public static BlockPos placementPosition(C08PacketPlayerBlockPlacement packet) {
        return new BlockPos(packet.getPlacedBlockX(), packet.getPlacedBlockY(), packet.getPlacedBlockZ());
    }

    public static C07PacketPlayerDigging digging(DiggingAction action, BlockPos pos, EnumFacing side) {
        return new C07PacketPlayerDigging(action.ordinal(), pos.getX(), pos.getY(), pos.getZ(), side.ordinal());
    }

    public static DiggingAction diggingStatus(C07PacketPlayerDigging packet) {
        return DiggingAction.values()[packet.getDiggedBlockStatus()];
    }

    /* 1.8.9 dropped the stance 1.7.10 still sends, which sits at the eyes. */
    public static C03PacketPlayer.C04PacketPlayerPosition position(double x, double y, double z, boolean onGround) {
        return new C03PacketPlayer.C04PacketPlayerPosition(x, y, y + 1.62, z, onGround);
    }

    public static C03PacketPlayer.C06PacketPlayerPosLook positionLook(double x, double y, double z, float yaw,
                                                                      float pitch, boolean onGround) {
        return new C03PacketPlayer.C06PacketPlayerPosLook(x, y, y + 1.62, z, yaw, pitch, onGround);
    }

    /* Interacting at a point on the entity is 1.8's; 1.7.10 only interacts. */
    public static C02PacketUseEntity interactAt(Entity entity, Vec3 point) {
        return new C02PacketUseEntity(entity, C02PacketUseEntity.Action.INTERACT);
    }

    public static PacketBuffer bufferData(S3FPacketCustomPayload packet) {
        return new PacketBuffer(Unpooled.wrappedBuffer(packet.func_149168_d()));
    }

    public static BlockPos blockPosition(S23PacketBlockChange packet) {
        return new BlockPos(packet.func_148879_d(), packet.func_148878_e(), packet.func_148877_f());
    }

    public static IBlockState blockState(S23PacketBlockChange packet) {
        return new BlockState(packet.func_148880_c(), packet.func_148881_g());
    }

    /* Four bytes a change: x, z and y packed into a short, then the block id and metadata into another. */
    public static BlockUpdateData[] changedBlocks(S22PacketMultiBlockChange packet) {
        ByteBuffer data = ByteBuffer.wrap(packet.func_148921_d());
        int chunkX = packet.func_148920_c().chunkXPos << 4;
        int chunkZ = packet.func_148920_c().chunkZPos << 4;
        BlockUpdateData[] changes = new BlockUpdateData[packet.func_148922_e()];
        for (int index = 0; index < changes.length && data.remaining() >= 4; ++index) {
            int position = data.getShort() & 0xFFFF;
            int block = data.getShort() & 0xFFFF;
            changes[index] = new BlockUpdateData(
                    new BlockPos(chunkX + (position >> 12 & 15), position & 255, chunkZ + (position >> 8 & 15)),
                    new BlockState(Block.getBlockById(block >> 4 & 4095), block & 15));
        }
        return changes;
    }

    public static byte chatType(S02PacketChat packet) {
        return (byte) (packet.isChat() ? 0 : 1);
    }

    public static Integer packetId(EnumConnectionState state, EnumPacketDirection direction, Packet packet) {
        return (Integer) state.func_150757_a(direction == EnumPacketDirection.CLIENTBOUND).inverse()
                .get(packet.getClass());
    }

    // -------------------------------------------------------------- render ----

    /* 1.7.10 gives every screen its own item renderer; this is the client's. */
    private static final RenderItem ITEMS = new RenderItem();

    public static RenderItem renderItem(Minecraft mc) {
        return ITEMS;
    }

    public static void renderItemIntoGui(RenderItem items, ItemStack stack, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        items.renderItemIntoGUI(mc.fontRendererObj, mc.getTextureManager(), stack, x, y);
    }

    public static void renderItemAndEffectIntoGui(RenderItem items, ItemStack stack, int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        items.renderItemAndEffectIntoGUI(mc.fontRendererObj, mc.getTextureManager(), stack, x, y);
    }

    /* 1.7.10 cannot switch entity shadows off, so they are always on and stay on. */
    public static boolean isRenderShadow(RenderManager manager) {
        return true;
    }

    public static void setRenderShadow(RenderManager manager, boolean shadow) {}

    /* There are no entity shaders or entity display lists to refresh before 1.8. */
    public static void loadEntityShader(EntityRenderer renderer, Entity entity) {}

    public static void setDisplayListEntitiesDirty(RenderGlobal global) {}

    public static int colorCode(FontRenderer font, char code) {
        return font.colorCode["0123456789abcdef".indexOf(code)];
    }

    /*
     * CheatBreaker took 1.8's text drawing, whose float calls vanilla 1.7.10
     * has no names for, and its (String, int, int, int) call that vanilla's
     * drawStringWithShadow pairs with draws centred. So text is drawn the way
     * 1.8's drawString does it, through the calls that kept vanilla's shape;
     * the width offsets the shift renderStringAligned makes for right-to-left
     * languages.
     */
    public static int drawString(FontRenderer font, String text, float x, float y, int colour, boolean shadow) {
        int left = MathHelper.floor_float(x);
        int top = MathHelper.floor_float(y);
        int width = font.getBidiFlag() ? font.getStringWidth(font.bidiReorder(text)) : 0;
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        font.resetStyles();
        int end = shadow ? font.renderStringAligned(text, left + 1, top + 1, width, colour, true) : 0;
        return Math.max(end, font.renderStringAligned(text, left, top, width, colour, false));
    }

    public static int drawStringWithShadow(FontRenderer font, String text, float x, float y, int colour) {
        return drawString(font, text, x, y, colour, true);
    }

    // ---------------------------------------------------------------- misc ----

    public static IBlockState storageBlockState(ExtendedBlockStorage storage, int x, int y, int z) {
        return new BlockState(storage.getBlockByExtId(x, y, z), storage.getExtBlockMetadata(x, y, z));
    }

    public static BlockPos tilePos(TileEntity tile) {
        return new BlockPos(tile.xCoord, tile.yCoord, tile.zCoord);
    }

    /* 1.7.10 only removes a player's scores from every objective at once, which for a sidebar line is the same. */
    public static void removeObjectiveFromEntity(Scoreboard scoreboard, String name, ScoreObjective objective) {
        scoreboard.func_96515_c(name);
    }

    // ----------------------------------------------------------- inventory ----

    public static IChatComponent inventoryDisplayName(IInventory inventory) {
        return new ChatComponentText(inventory.getInventoryName());
    }

    public static String inventoryName(IInventory inventory) {
        return inventory.getInventoryName();
    }

    public static boolean inventoryHasCustomName(IInventory inventory) {
        return inventory.isCustomInventoryName();
    }
}
