package cobble.v1_21.compat;

import com.google.common.base.Predicate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 1.8.9's World on 1.21.11's. The client only ever has a ClientWorld, whose
 * entity and player lists are what 1.8's loaded lists were; they are copies
 * here, as 1.21 keeps entities in sections rather than one list.
 */
public final class WorldShim {
    private WorldShim() {}

    public static boolean isRemote(World world) {
        return world.isClient();
    }

    public static List<Entity> loadedEntityList(World world) {
        List<Entity> entities = new ArrayList<>();
        ((ClientWorld) world).getEntities().forEach(entities::add);
        return entities;
    }

    /* 1.21 keeps block entities in their chunks; these are the ones within the view distance. */
    public static List<BlockEntity> loadedTileEntityList(World world) {
        List<BlockEntity> tiles = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return tiles;
        }
        int range = client.options.getClampedViewDistance();
        ChunkPos centre = client.player.getChunkPos();
        for (int x = centre.x - range; x <= centre.x + range; ++x) {
            for (int z = centre.z - range; z <= centre.z + range; ++z) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) {
                    tiles.addAll(chunk.getBlockEntities().values());
                }
            }
        }
        return tiles;
    }

    /** 1.8's WorldProvider is the world itself here; see WorldProviderShim. */
    public static World provider(World world) {
        return world;
    }

    public static boolean isMaterialInBB(World world, Box box, Material material) {
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY),
                MathHelper.floor(box.minZ), MathHelper.floor(box.maxX), MathHelper.floor(box.maxY),
                MathHelper.floor(box.maxZ))) {
            BlockState state = world.getBlockState(pos);
            boolean matches = material == Material.water ? state.getFluidState().isIn(FluidTags.WATER)
                    : material == Material.lava ? state.getFluidState().isIn(FluidTags.LAVA)
                    : Material.of(state.getBlock()) == material;
            if (matches) {
                return true;
            }
        }
        return false;
    }

    public static List<PlayerEntity> playerEntities(World world) {
        return new ArrayList<>(world.getPlayers());
    }

    public static boolean checkNoEntityCollision(World world, Box box, Entity entity) {
        return world.doesNotIntersectEntities(entity, VoxelShapes.cuboid(box));
    }

    public static WorldChunk getChunkFromChunkCoords(World world, int x, int z) {
        return world.getChunk(x, z);
    }

    public static List<Box> getCollidingBoundingBoxes(World world, Entity entity, Box box) {
        List<Box> boxes = new ArrayList<>();
        for (VoxelShape shape : world.getBlockCollisions(entity, box)) {
            boxes.addAll(shape.getBoundingBoxes());
        }
        return boxes;
    }

    public static List<Entity> getEntitiesInAABBexcluding(World world, Entity excluded, Box box,
                                                          Predicate<? super Entity> filter) {
        return world.getOtherEntities(excluded, box, filter == null ? entity -> true : filter::apply);
    }

    public static Entity getEntityByID(World world, int id) {
        return world.getEntityById(id);
    }

    public static PlayerEntity getPlayerEntityByName(World world, String name) {
        for (PlayerEntity player : world.getPlayers()) {
            if (player.getName().getString().equals(name)) {
                return player;
            }
        }
        return null;
    }

    public static long getTotalWorldTime(World world) {
        return world.getTime();
    }

    public static boolean isBlockLoaded(World world, BlockPos pos) {
        return world.isPosLoaded(pos);
    }

    /*
     * 1.8 hands back null when the ray meets nothing, and with the last flag
     * set the last block it passed even if that one had no collision box;
     * 1.21's ray has no such block to report, so that case is a miss too.
     */
    public static HitResult rayTraceBlocks(World world, Vec3d from, Vec3d to, boolean stopOnLiquid,
                                           boolean ignoreNoBox, boolean returnLast) {
        RaycastContext context = new RaycastContext(from, to,
                ignoreNoBox ? RaycastContext.ShapeType.COLLIDER : RaycastContext.ShapeType.OUTLINE,
                stopOnLiquid ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE,
                ShapeContext.absent());
        BlockHitResult hit = world.raycast(context);
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }
}
