package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;

/** 1.8.9's AbstractClientPlayer on 1.21.11's AbstractClientPlayerEntity. */
public final class AbstractClientPlayerShim {
    private AbstractClientPlayerShim() {}

    public static Identifier getLocationSkin(AbstractClientPlayerEntity player) {
        return player.getSkin().body().texturePath();
    }

    public static PlayerListEntry getPlayerInfo(AbstractClientPlayerEntity player) {
        return MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(player.getUuid());
    }
}
