package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 1.8.9's C08PacketPlayerBlockPlacement, which was both a block placement and
 * an item use, as 1.21.11's two packets: the class is any packet, and a test
 * against it matches either. 1.8 told the item use apart by face 255 at
 * -1, -1, -1. Neither 1.21 packet carries the stack; it is the held one.
 */
public final class C08PacketPlayerBlockPlacementShim {
    private static final int ITEM_USE = 255;
    private static final BlockPos NO_BLOCK = new BlockPos(-1, -1, -1);

    private C08PacketPlayerBlockPlacementShim() {}

    public static boolean isPlacement(Object packet) {
        return packet instanceof PlayerInteractBlockC2SPacket || packet instanceof PlayerInteractItemC2SPacket;
    }

    public static Packet<?> create(ItemStack stack) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        return new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, player.getYaw(), player.getPitch());
    }

    public static Packet<?> create(BlockPos pos, int face, ItemStack stack, float x, float y, float z) {
        if (face == ITEM_USE) {
            return create(stack);
        }
        BlockHitResult hit = new BlockHitResult(new Vec3d(pos.getX() + x, pos.getY() + y, pos.getZ() + z),
                Direction.byIndex(face), pos, false);
        return new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0);
    }

    public static int getPlacedBlockDirection(Packet<?> packet) {
        return packet instanceof PlayerInteractBlockC2SPacket block ? block.getBlockHitResult().getSide().getIndex()
                : ITEM_USE;
    }

    public static BlockPos getPosition(Packet<?> packet) {
        return packet instanceof PlayerInteractBlockC2SPacket block ? block.getBlockHitResult().getBlockPos()
                : NO_BLOCK;
    }

    public static ItemStack getStack(Packet<?> packet) {
        return ItemStacks.orNull(MinecraftClient.getInstance().player.getMainHandStack());
    }
}
