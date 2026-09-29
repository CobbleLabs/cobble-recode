package cobble.util;

import cobble.module.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Random;

public final class AQKBeof5 {
    private AQKBeof5() {
    }

    public static double randomBetweenSliders(Setting var0, Setting var1, Random var2) {
        double var3 = var0.number();
        double var5 = var1.number();
        return var3 == var5 ? var3 : var3 + var2.nextDouble() * (var5 - var3);
    }

    public static String getJsonString(JsonObject var0, String var1) {
        try {
            JsonElement var2 = var0.get(var1);
            return var2.getAsString();
        } catch (RuntimeException var3) {
            return "";
        }
    }

    public static <E extends Enum<E>> E findEnumByName(Class<E> var0, String var1) {
        Enum[] var2 = (Enum[])var0.getEnumConstants();

        for (int var3 = 0; var3 < var2.length; var3++) {
            Enum var4 = var2[var3];
            if (var4.name().equals(var1)) {
                return (E)var4;
            }
        }

        return null;
    }

    public static String readStreamText(InputStream var0) {
        StringBuilder var1 = new StringBuilder();

        try {
            BufferedReader var2 = new BufferedReader(new InputStreamReader(var0));

            for (String var3 = var2.readLine(); var3 != null; var3 = var2.readLine()) {
                var1.append(var3).append('\n');
            }
        } catch (Exception var4) {
            var4.printStackTrace();
        }

        return var1.toString();
    }
}
