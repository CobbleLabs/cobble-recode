package cobble.module.render;

import cobble.core.Color;
import cobble.event.Phase;
import cobble.event.RenderWorldLastEvent;
import cobble.event.Subscribe;
import cobble.event.TickEndEvent;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.module.other.AntiBot;
import cobble.util.ClientUtils;
import cobble.util.render.RenderUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

public class Tracers extends LegacyModule {
    public Setting lineWidth;
    public Setting maxDistance;
    public Setting color;
    public Setting teamColor;
    public Setting offScreenOnly;
    public Setting hideTeammates;
    public Setting onlyInGame;
    private final Setting colorByDistance;
    private final Setting highlightFocusing;
    private boolean rVdeD;
    private final ArrayList<Entity> entitys = new ArrayList<>();
    private int entityCount = 0;

    public Tracers() {
        super("Tracers", "Lines from the crosshair to players", Category.VISUALS, Subcategory.MEDIUM);
        this.lineWidth = addNumber("Line Width", "", 1.0F, 1.0F, 5.0F, 0);
        this.maxDistance = addNumber("Max distance", "", 128.0F, 10.0F, 512.0F, 0);
        this.color = addColour("Color", "", Color.rgb(0x00FF00));
        this.teamColor = addBoolean("Team color", "", false);
        this.offScreenOnly = addBoolean("Off-screen only", "", false);
        this.hideTeammates = addBoolean("Hide teammates", "", false);
        this.onlyInGame = addBoolean("Only in-game", "", false);
        this.colorByDistance = addBoolean("Color by distance", "Red up close, green past the middle of the pack",
                false);
        this.highlightFocusing = addBoolean("Highlight if focusing",
                "Thicken the tracer of anyone looking straight at you", false);
    }

    @Override
    public void onEnable(Context context) {
        this.rVdeD = mc.gameSettings.viewBobbing;
        if (this.rVdeD) {
            mc.gameSettings.viewBobbing = false;
        }
    }

    @Override
    public void onDisable(Context context) {
        mc.gameSettings.viewBobbing = this.rVdeD;
    }

    @Override
    public void onUpdate() {
        if (mc.gameSettings.viewBobbing) {
            mc.gameSettings.viewBobbing = false;
        }
    }

    @Subscribe
    public void onTickEnd(TickEndEvent var1) {
        if (var1.phase == Phase.END) {
            this.abeK();
        }
    }

    @Subscribe
    public void onRenderWorldLast(RenderWorldLastEvent var1) {
        if (ClientUtils.isInWorld() && this.entityCount != 0) {
            int var2 = this.color.colour().argb();
            double median = this.colorByDistance.bool() ? this.medianDistance() : 0.0;
            boolean anyFocusing = this.highlightFocusing.bool() && this.anyFocusing();

            for (int var3 = 0; var3 < this.entityCount; var3++) {
                Entity var4 = this.entitys.get(var3);
                if (var4 != null && (!this.offScreenOnly.bool() || !RenderUtils.isEntityInView(var4))) {
                    int var5 = this.colorByDistance.bool()
                            ? distanceColor(mc.thePlayer.getDistanceToEntity(var4), median)
                            : this.applyTeamColor(var4, var2);
                    float width = (float)this.lineWidth.number();
                    if (anyFocusing) {
                        width *= focusing(var4) ? 3.0f : 0.75f;
                    }
                    RenderUtils.drawTracerToEntity(var4, var5, width, var1.partialTicks);

                }
            }
        }
    }

    private double medianDistance() {
        double[] distances = new double[this.entityCount];
        for (int index = 0; index < this.entityCount; index++) {
            distances[index] = mc.thePlayer.getDistanceToEntity(this.entitys.get(index));
        }
        Arrays.sort(distances);
        return Math.max(distances[distances.length / 2], 1.0);
    }

    /* Nearer than the middle of the pack runs from red to green in three-block steps; past it stays green and fades. */
    private static int distanceColor(double distance, double median) {
        float maximumHue = 0.35f;
        double offset = Math.round(distance / 3.0) * 3L - median / 3.0;
        float hue = distance > median ? maximumHue : (float) (maximumHue * (offset / median));
        int alpha = distance > median ? Math.max(150, (int) (255.0 / (distance / median))) : 255;
        return Color.hsv(hue, 1.0f, 1.0f, alpha / 255.0f).argb();
    }

    private boolean anyFocusing() {
        for (int index = 0; index < this.entityCount; index++) {
            if (focusing(this.entitys.get(index))) {
                return true;
            }
        }
        return false;
    }

    /* Where their look lands at your distance is within five blocks of you, on the ground plane. */
    private static boolean focusing(Entity entity) {
        double distance = entity.getDistanceToEntity(mc.thePlayer);
        double yaw = Math.toRadians(entity.rotationYaw + 90.0f);
        double aimX = entity.posX + Math.cos(yaw) * distance;
        double aimZ = entity.posZ + Math.sin(yaw) * distance;
        return Math.hypot(aimX - mc.thePlayer.posX, aimZ - mc.thePlayer.posZ) < 5.0;
    }

    private int applyTeamColor(Entity var1, int var2) {
        if (this.teamColor.bool() && var1 instanceof EntityPlayer) {
            int var3 = ClientUtils.oCoqd(var1);
            if (var3 != -1) {
                int var4 = var2 >> 24 & 0xFF;
                return var4 << 24 | var3 & 16777215;
            }
        }

        return var2;
    }

    private void abeK() {
        this.entityCount = 0;
        if (ClientUtils.isInWorld() && mc.theWorld != null) {
            if (!this.onlyInGame.bool() || isInBedwarsGame()) {
                double var1 = this.maxDistance.number();
                double var3 = var1 * var1;
                for (EntityPlayer var6 : mc.theWorld.playerEntities) {
                    if (var6 != mc.thePlayer
                        && var6.deathTime == 0
                        && !AntiBot.shouldHideEntity(var6)
                        && (!this.hideTeammates.bool() || !ClientUtils.isTeammate(var6))
                        && !(mc.thePlayer.getDistanceSqToEntity(var6) > var3)) {
                        this.addEntity(var6);
                    }
                }

            }
        }
    }

    private void addEntity(Entity var1) {
        if (this.entityCount >= this.entitys.size()) {
            this.entitys.add(var1);
        } else {
            this.entitys.set(this.entityCount, var1);
        }

        this.entityCount++;
    }

    private static boolean isInBedwarsGame() {
        List var0 = ClientUtils.PxSw4();
        if (var0.size() < 7) {
            return false;
        } else if (!ClientUtils.zaUnpz((String)var0.get(0)).startsWith("BED WARS")) {
            return false;
        } else {
            String var1 = ClientUtils.zaUnpz((String)var0.get(1));
            String[] var2 = var1.split("  ");
            if (var2.length > 1) {
                String var3 = var2[1];
                if (var3.endsWith("]")) {
                    var3 = var3.split(" ")[0];
                }

                if (var3.startsWith("L")) {
                    return false;
                }
            }

            if (ClientUtils.zaUnpz((String)var0.get(5)).startsWith("R Red:") && ClientUtils.zaUnpz((String)var0.get(6)).startsWith("B Blue:")) {
                return true;
            } else {
                String var4 = ClientUtils.zaUnpz((String)var0.get(6));
                return !var4.equals("Waiting...") && !var4.startsWith("Starting in") ? false : false;
            }
        }
    }

}
