package cobble.v1_21.compat;

import com.mojang.authlib.GameProfile;
import java.util.UUID;

/** 1.8.9's authlib GameProfile on 1.21.11's, which is a record. */
public final class GameProfileShim {
    private GameProfileShim() {}

    public static String getName(GameProfile profile) {
        return profile.name();
    }

    public static UUID getId(GameProfile profile) {
        return profile.id();
    }
}
