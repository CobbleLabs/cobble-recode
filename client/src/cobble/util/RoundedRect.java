package cobble.util;

import cobble.module.render.shared.ShaderProgram;
import java.awt.Color;

public final class RoundedRect {
    public static ShaderProgram ABgU = new ShaderProgram("roundedRect");
    public static ShaderProgram outlineShader = new ShaderProgram("roundRectOutline");
    private static final ShaderProgram AkT = new ShaderProgram("roundRectTexture");
    private static final ShaderProgram gradientShader = new ShaderProgram("roundedRectGradient");
    private static final ShaderProgram CMqQ = new ShaderProgram("roundedRectRise");

    private RoundedRect() {
    }

    public static void drawRoundedRectArgb(float var0, float var1, float var2, float var3, float var4, int var5) {
        RoundedShader.Fzsi8(ABgU, var0, var1, var2, var3, var4, false, var5);
    }

    public static void drawVerticalGradient(float var0, float var1, float var2, float var3, float var4, Color var5, Color var6) {
        drawFourCornerGradient(var0, var1, var2, var3, var4, var5, var5, var6, var6);
    }

    public static void drawHorizontalGradient(float var0, float var1, float var2, float var3, float var4, Color var5, Color var6) {
        drawFourCornerGradient(var0, var1, var2, var3, var4, var6, var5, var6, var5);
    }

    public static void drawFourCornerGradient(float var0, float var1, float var2, float var3, float var4, Color var5, Color var6, Color var7, Color var8) {
        drawFourCornerGradientArgb(var0, var1, var2, var3, var4, var5.getRGB(), var6.getRGB(), var7.getRGB(), var8.getRGB());
    }

    public static void drawFourCornerGradientArgb(float var0, float var1, float var2, float var3, float var4, int var5, int var6, int var7, int var8) {
        RoundedShader.drawRoundedGradient(gradientShader, var0, var1, var2, var3, var4, var5, var6, var7, var8);
    }

    public static void drawRoundedOutline(float var0, float var1, float var2, float var3, float var4, float var5, Color var6, Color var7) {
        RoundedShader.drawRoundedOutline(outlineShader, var0, var1, var2, var3, var4, var5, var6.getRGB(), var7.getRGB());
    }

    public static void drawRoundedRectWithCornerFlags(float var0, float var1, float var2, float var3, float var4, int var5, boolean var6, boolean var7, boolean var8, boolean var9) {
        ZFcV(var0, var1, var2, var3, var4, var5, var6, var7, var8, var9, false, false);
    }

    public static void ZFcV(
        float var0,
        float var1,
        float var2,
        float var3,
        float var4,
        int var5,
        boolean var6,
        boolean var7,
        boolean var8,
        boolean var9,
        boolean var10,
        boolean var11
    ) {
        RoundedShader.drawRoundedRectWithCornerFlags(CMqQ, var0, var1, var2, var3, var4, var5, var6, var7, var8, var9, var10, var11);
    }

    public static void drawRoundedRectPerCornerRadii(
        float var0, float var1, float var2, float var3, int var4, float var5, float var6, float var7, float var8, boolean var9, boolean var10
    ) {
        RoundedShader.drawRoundedRectPerCornerRadii(CMqQ, var0, var1, var2, var3, var4, var5, var6, var7, var8, var9, var10);
    }
}
