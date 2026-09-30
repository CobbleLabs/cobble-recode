package cobble.module.render;

import cobble.core.Color;
import cobble.core.Vec3;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.Projection.ScreenPoint;
import cobble.game.Raycast;
import cobble.game.Raycast.BlockHit;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.render.Renderer;
import cobble.ui.Theme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemExpBottle;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;

/**
 * The Raven-b {@code trajectories} script.
 *
 * <p>Simulates the projectile in hand forward tick by tick using vanilla's own
 * numbers - a per-item gravity, a launch speed and angle, and for a bow the
 * charge curve - and draws the path it takes until something stops it. The line
 * is weighted by what that something was, so the readout is not just "it lands
 * there" but "it lands on them", "it hits that wall" or "it drops short".
 *
 * <p>The path is drawn in the world, not on the HUD: every point is projected
 * with the frame's own view-projection matrix, so the arc is welded to the world
 * while the camera moves.
 *
 * <p>The original also trailed thrown projectiles that were already in flight.
 * That is left out here: the frame capture keeps living entities only, and
 * widening it to every entity in render distance would cost the whole client a
 * per-frame walk it currently does not do.
 */
public final class Trajectories extends Module {
    /* Four points per tick. Fewer and the arc is visibly a polyline at close
     * range; more is spent on segments a pixel long. */
    private static final int SUB_STEPS = 4;
    /* How far vanilla grows every entity box when testing a projectile against it. */
    private static final double HIT_RADIUS = 0.3;
    private static final double DRAG = 0.99;
    private static final double VOID_Y = -64.0;
    private static final float FADE_SECONDS = 2.0f;
    /* The first half block of the arc leaves from beside the player's own hand,
     * so it is clipped rather than drawn across the middle of the screen. */
    private static final double HIDDEN_LENGTH = 0.5;

    private static final int[][] EDGES = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4},
            {1, 5}, {2, 6}, {3, 7}};

    /** Launch parameters; {@code pitchOffset} is vanilla's per-entity tilt of the throw in degrees. */
    private record Projectile(double gravity, double velocity, double pitchOffset) {}

    private final Setting lineWidth = addNumber("Line width", "Arc thickness in pixels", 2, 1, 5, 1);
    private final Setting maxTicks = addNumber("Max ticks", "How far ahead to simulate", 100, 30, 200, 0);
    private final Setting landingBox = addBoolean("Landing box", "Cube at the impact point", true);
    private final Setting landingCross = addBoolean("Landing cross", "Three axes through the impact point", true);
    private final Setting landingBlock = addBoolean("Landing block", "Outline the block that stops it", true);
    private final Setting highlight = addBoolean("Highlight entities", "Outline whoever the arc lands on", true);
    private final Setting playerVelocity = addBoolean("Add player velocity", "Include your own motion in the launch",
            false);
    private final Setting fadeOut = addBoolean("Fade out", "Leave the last arc behind for a moment", true);

    private List<Vec3> lastPath = List.of();
    private float lastPathAge = 99.0f;

    public Trajectories() {
        super("Trajectories", "Draws where the projectile in your hand lands", Category.VISUALS, Subcategory.BASIC);
        setIcon(Icon.ACTIVITY);
    }

    @Override
    public void onEnable(Context context) {
        lastPath = List.of();
        lastPathAge = 99.0f;
    }

    /** What the held item is launched with, or null when it is not a projectile. */
    private static Projectile properties(FrameState state) {
        ItemStack stack = Game.heldItem();
        if (stack == null) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof ItemBow) {
            /* Vanilla's charge curve, and the reason a half-drawn bow is worth so
             * much less than it looks: the speed is quadratic in the draw. */
            float charge = (state.player.itemUseTicks + state.tickProgress) / 20.0f;
            charge = Math.min((charge * charge + charge * 2.0f) / 3.0f, 1.0f);
            return charge < 0.1f ? null : new Projectile(0.05, charge * 3.0, 0.0);
        }
        if (item instanceof ItemSnowball || item instanceof ItemEgg || item instanceof ItemEnderPearl) {
            return new Projectile(0.03, 1.5, 0.0);
        }
        /* Bottles and splash potions are lobbed twenty degrees above the look. */
        if (item instanceof ItemExpBottle) {
            return new Projectile(0.07, 0.7, -20.0);
        }
        if (item instanceof ItemPotion && ItemPotion.isSplash(stack.getMetadata())) {
            return new Projectile(0.05, 0.5, -20.0);
        }
        return null;
    }

    private static void line3d(Context context, Vec3 from, Vec3 to, float width, Color colour) {
        ScreenPoint a = context.projection.project(from);
        ScreenPoint b = context.projection.project(to);
        if (a != null && b != null) {
            Renderer.instance().line(a.x(), a.y(), b.x(), b.y(), width, colour);
        }
    }

    private static void outlinedBox(Context context, Vec3 minimum, Vec3 maximum, float width, Color colour) {
        Vec3[] corner = new Vec3[8];
        for (int index = 0; index < 8; ++index) {
            corner[index] = new Vec3((index & 1) != 0 ? maximum.x() : minimum.x(),
                    (index & 2) != 0 ? maximum.y() : minimum.y(), (index & 4) != 0 ? maximum.z() : minimum.z());
        }
        for (int[] edge : EDGES) {
            line3d(context, corner[edge[0]], corner[edge[1]], width, colour);
        }
    }

    private static Vec3 grow(Vec3 point, double amount) {
        return point.add(amount, amount, amount);
    }

    @Override
    public void onRender(Context context) {
        lastPathAge += context.deltaTime;

        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || !context.projection.valid()) {
            return;
        }

        float width = lineWidth.number();
        float alpha = animation();

        Projectile projectile = properties(state);
        if (projectile == null) {
            /* The arc the last throw would have taken, dimming out. Without it the
             * line vanishes the instant the item leaves the hand, which is the one
             * moment somebody is looking at it. */
            if (fadeOut.bool() && lastPath.size() >= 2 && lastPathAge < FADE_SECONDS) {
                Color colour = Theme.accentShade(0.7f, (1.0f - lastPathAge / FADE_SECONDS) * alpha);
                for (int index = 1; index < lastPath.size(); ++index) {
                    line3d(context, lastPath.get(index - 1), lastPath.get(index), width, colour);
                }
            }
            return;
        }

        /* Where 1.8.9 creates both arrows and throwables: a tenth of a block below
         * the eye and sixteen hundredths to the right of it, moving parallel to
         * the look, so the arc starts beside the crosshair and converges on it. */
        double yaw = Math.toRadians(state.player.yaw);
        double pitch = Math.toRadians(state.player.pitch);
        Vec3 position = new Vec3(state.player.pos.x() - Math.cos(yaw) * 0.16, state.player.eyePos.y() - 0.1,
                state.player.pos.z() - Math.sin(yaw) * 0.16);

        Vec3 motion = new Vec3(-Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch + Math.toRadians(projectile.pitchOffset())), Math.cos(yaw) * Math.cos(pitch));
        motion = motion.mul(projectile.velocity() / motion.length());
        if (playerVelocity.bool()) {
            motion = motion.add(state.player.velocity);
        }

        List<Vec3> path = new ArrayList<>();
        Vec3 hitPoint = null;
        EntityView struck = null;
        BlockHit landed = null;

        int ticks = (int) maxTicks.number();
        for (int tick = 0; tick < ticks; ++tick) {
            double step = motion.length();
            if (step > 1e-9) {
                Vec3 direction = motion.mul(1.0 / step);

                /* Whichever stops it first. Both tests are run against the same
                 * segment, so "an arrow that clips the wall behind them" is the
                 * wall and not the kill it looks like. */
                double entityAlong = Double.MAX_VALUE;
                for (EntityView entity : state.entities) {
                    if (entity.health <= 0.0f) {
                        continue;
                    }
                    double along = Raycast.rayBox(position, direction, grow(entity.boxMin(), -HIT_RADIUS),
                            grow(entity.boxMax(), HIT_RADIUS), step);
                    if (along >= 0.0 && along < entityAlong) {
                        entityAlong = along;
                        struck = entity;
                    }
                }
                BlockHit block = Raycast.raycastBlocks(position, direction, step);

                if (struck != null && block != null && block.distance() <= entityAlong) {
                    struck = null;
                }
                if (struck != null || block != null) {
                    double along = struck != null ? entityAlong : block.distance();
                    int substeps = (int) Math.ceil(along / step * SUB_STEPS);
                    for (int sub = 0; sub < substeps; ++sub) {
                        path.add(position.add(motion.mul((double) sub / SUB_STEPS)));
                    }
                    hitPoint = struck != null ? position.add(direction.mul(along)) : block.point();
                    landed = struck != null ? null : block;
                    path.add(hitPoint);
                    break;
                }
            }

            for (int sub = 0; sub < SUB_STEPS; ++sub) {
                path.add(position.add(motion.mul((double) sub / SUB_STEPS)));
            }
            position = position.add(motion);
            motion = motion.mul(DRAG).add(0.0, -projectile.gravity(), 0.0);
            if (position.y() < VOID_Y) {
                break;
            }
        }

        /* One colour, three weights. An arc that lands on somebody is the accent
         * lifted towards white, one that stops on a block is the accent as it is,
         * and one that simply runs out of ticks is stepped down - so the throw
         * worth taking is the brightest thing on screen without introducing a
         * second hue to read. The landing marker says the rest: it is twice the
         * size for an entity hit. */
        float weight = struck != null ? 1.4f : (landed != null ? 1.0f : 0.7f);
        Color colour = Theme.accentShade(weight, alpha);

        double skipped = 0.0;
        int first = 1;
        while (first < path.size() && skipped < HIDDEN_LENGTH) {
            skipped += path.get(first).sub(path.get(first - 1)).length();
            ++first;
        }
        for (int index = first; index < path.size(); ++index) {
            line3d(context, path.get(index - 1), path.get(index), width, colour);
        }

        if (hitPoint != null) {
            double boxSize = struck != null ? 0.4 : 0.2;
            double crossSize = struck != null ? 0.5 : 0.3;
            if (landingBox.bool()) {
                outlinedBox(context, grow(hitPoint, -boxSize), grow(hitPoint, boxSize), 2.0f, colour);
            }
            if (landingCross.bool()) {
                line3d(context, hitPoint.add(-crossSize, 0, 0), hitPoint.add(crossSize, 0, 0), 1.5f, colour);
                line3d(context, hitPoint.add(0, -crossSize, 0), hitPoint.add(0, crossSize, 0), 1.5f, colour);
                line3d(context, hitPoint.add(0, 0, -crossSize), hitPoint.add(0, 0, crossSize), 1.5f, colour);
            }
            if (landingBlock.bool() && landed != null) {
                Vec3 cell = new Vec3(landed.x(), landed.y(), landed.z());
                outlinedBox(context, grow(cell, 0.005), grow(cell, 0.995), 1.5f, colour);
            }
        }

        if (highlight.bool() && struck != null) {
            outlinedBox(context, grow(struck.boxMin(), -0.3), grow(struck.boxMax(), 0.3), 2.0f, colour);
        }

        if (fadeOut.bool()) {
            lastPath = path;
            lastPathAge = 0.0f;
        }
    }
}
