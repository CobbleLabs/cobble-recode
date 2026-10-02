package cobble.module.render;

import cobble.core.Assets;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

public class Cape extends Module {
    private static Cape instance;
    public static Setting cape;
    private String[] capeModes;
    private List<String> capeEntries = new ArrayList<>();
    public static List<List<ResourceLocation>> capeFrames = new ArrayList<>();
    private CapeShimmer shimmer;
    private int shimmerIndex = -1;

    public Cape() {
        super("Cape", "Gives you a cape", Category.VISUALS, Subcategory.BASIC);
        instance = this;
        this.KBIxnC();
        cape = addChoice("Cape", "", List.of(this.capeModes), 0);
    }

    private void KBIxnC() {
        ArrayList var1 = new ArrayList();

        for (String var5 : new String(Assets.read("assets/cobble/textures/capes/capes.txt"), StandardCharsets.UTF_8).split("\n")) {
            var5 = var5.trim();
            if (!var5.isEmpty()) {
                var1.add(var5);
            }
        }

        this.capeEntries = var1;
        ArrayList var21 = new ArrayList();
        var21.add("None");

        for (String var31 : this.capeEntries) {
            int var35 = var31.indexOf(47);
            if (var35 != -1) {
                var21.add(var31.substring(0, var35));
            } else {
                int var39 = var31.lastIndexOf(46);
                var21.add(var39 > 0 ? var31.substring(0, var39) : var31);
            }
        }

        this.capeModes = (String[]) var21.toArray(new String[0]);
    }

    public static boolean selected() {
        return instance != null && instance.enabled() && cape.choice() > 0;
    }

    /** The selected cape's current frame, or null for none. */
    public static ResourceLocation texture() {
        if (!selected()) {
            return null;
        }

        if (capeFrames.isEmpty()) {
            instance.loadCapeTextures();
        }

        int var0 = cape.choice() - 1;
        if (var0 >= capeFrames.size()) {
            return null;
        }

        if (var0 == instance.shimmerIndex) {
            instance.shimmer.update();
        }

        List<ResourceLocation> var1 = capeFrames.get(var0);
        if (var1.isEmpty()) {
            return null;
        }

        return var1.size() == 1 ? var1.get(0) : var1.get((int)(System.currentTimeMillis() / 67L % var1.size()));
    }

    public void loadCapeTextures() {
        capeFrames.clear();
        shimmer = null;
        shimmerIndex = -1;
        String var1 = "/assets/cobble/textures/capes/";

        try {
            for (String var3 : this.capeEntries) {
                int var4 = var3.indexOf(47);
                if (var4 != -1) {
                    String var16 = var3.substring(0, var4);
                    String var17 = var3.substring(var4 + 1);
                    String[] var18 = var17.split(",");
                    ArrayList var19 = new ArrayList();

                    for (String var23 : var18) {
                        var23 = var23.trim();
                        if (!var23.isEmpty()) {
                            InputStream var25 = Assets.open(var1 + var16 + "/" + var23);
                            if (var25 != null) {
                                BufferedImage var26 = ImageIO.read(var25);
                                var25.close();
                                if (var26 != null) {
                                    var19.add(mc.getTextureManager().getDynamicTextureLocation(var16 + "_" + var23, new DynamicTexture(var26)));
                                }
                            }
                        }
                    }

                    capeFrames.add(var19);
                } else {
                    String var5 = var3.substring(0, var3.lastIndexOf(46));
                    InputStream var6 = Assets.open(var1 + var3);
                    if (var6 == null) {
                        capeFrames.add(new ArrayList<>());
                    } else {
                        BufferedImage var7 = ImageIO.read(var6);
                        var6.close();
                        if (var7 == null) {
                            capeFrames.add(new ArrayList<>());
                        } else {
                            int var8 = var7.getWidth();
                            int var9 = var7.getHeight();
                            int var10 = Math.max(1, var8 / 2);
                            ArrayList var11 = new ArrayList();
                            CapeShimmer lettering = var5.equals("Cobble") ? CapeShimmer.of(var7) : null;
                            if (lettering != null) {
                                shimmer = lettering;
                                shimmerIndex = capeFrames.size();
                                var11.add(mc.getTextureManager().getDynamicTextureLocation(var5, lettering.texture));
                            } else if (var9 > var10 && var9 % var10 == 0) {
                                int var12 = var9 / var10;

                                for (int var13 = 0; var13 < var12; var13++) {
                                    BufferedImage var14 = var7.getSubimage(0, var13 * var10, var8, var10);
                                    var11.add(mc.getTextureManager().getDynamicTextureLocation(var5 + "_f" + var13, new DynamicTexture(var14)));
                                }
                            } else {
                                var11.add(mc.getTextureManager().getDynamicTextureLocation(var5, new DynamicTexture(var7)));
                            }

                            capeFrames.add(var11);
                        }
                    }
                }
            }
        } catch (Exception var15) {
            var15.printStackTrace();
        }
    }
}
