package cobble.module.render;

import cobble.core.Color;
import cobble.core.Rect;
import cobble.core.Vec3;
import cobble.game.Projection.ScreenPoint;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Align;
import cobble.render.FontRole;
import cobble.render.Renderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityMobSpawner;

/** Labels every loaded mob spawner with what it spawns, and how far away it is. */
public final class SpawnerFinder extends Module {
    private static final float TEXT_SIZE = 8.0f;
    private static final Color BACKGROUND = Color.rgb(0x141414, 0.5f);
    private static final Color DISTANCE = Color.rgb(0x55FF55);

    private final Setting scale = addNumber("Scale", "Label size", 1.0f, 0.1f, 1.5f, 1);
    private final Setting showDistance = addBoolean("Show distance", "Prefix the distance in blocks", true);
    private final Setting zombie = addBoolean("Zombie", "Label zombie spawners", true);
    private final Setting skeleton = addBoolean("Skeleton", "Label skeleton spawners", true);
    private final Setting creeper = addBoolean("Creeper", "Label creeper spawners", true);
    private final Setting spider = addBoolean("Spider", "Label spider spawners", true);
    private final Setting others = addBoolean("Others", "Label spawners of every other mob", false);

    public SpawnerFinder() {
        super("SpawnerFinder", "Labels mob spawners through walls", Category.VISUALS, Subcategory.MEDIUM);
    }

    @Override
    public void onRender(Context context) {
        if (mc.theWorld == null || mc.thePlayer == null || !context.projection.valid()) {
            return;
        }
        Renderer gfx = Renderer.instance();
        float size = TEXT_SIZE * scale.number() * context.state.scaleFactor;
        float pad = size * 0.35f;
        for (TileEntity tile : mc.theWorld.loadedTileEntityList) {
            if (!(tile instanceof TileEntityMobSpawner spawner)) {
                continue;
            }
            String mob = spawner.getSpawnerBaseLogic().getEntityNameToSpawn();
            if (!shown(mob)) {
                continue;
            }
            ScreenPoint point = context.projection.project(new Vec3(tile.getPos().getX() + 0.5,
                    tile.getPos().getY() + 0.5, tile.getPos().getZ() + 0.5));
            if (point == null) {
                continue;
            }
            String blocks = String.valueOf((int) Math.sqrt(mc.thePlayer.getDistanceSq(tile.getPos())));
            String name = mob + " spawner";
            String full = showDistance.bool() ? "[" + blocks + "] " + name : name;
            float width = gfx.measure(FontRole.MEDIUM, full, size);
            float height = gfx.lineHeight(FontRole.MEDIUM, size);
            float x = point.x() - width * 0.5f;
            float y = point.y();
            gfx.rect(new Rect(x - pad, y - pad, width + pad * 2.0f, height + pad * 2.0f), BACKGROUND, pad);
            /* The distance in white between green brackets. */
            if (showDistance.bool()) {
                x = gfx.text(FontRole.MEDIUM, "[", x, y, size, DISTANCE, Align.LEFT);
                x = gfx.text(FontRole.MEDIUM, blocks, x, y, size, Color.WHITE, Align.LEFT);
                x = gfx.text(FontRole.MEDIUM, "] ", x, y, size, DISTANCE, Align.LEFT);
            }
            gfx.text(FontRole.MEDIUM, name, x, y, size, Color.WHITE, Align.LEFT);
        }
    }

    private boolean shown(String mob) {
        return switch (mob) {
            case "Zombie" -> zombie.bool();
            case "Skeleton" -> skeleton.bool();
            case "Creeper" -> creeper.bool();
            case "Spider" -> spider.bool();
            default -> others.bool();
        };
    }
}
