package cobble.v1_21.compat;

import java.util.UUID;
import net.minecraft.client.session.Session;

/** 1.8.9's Session on 1.21.11's; 1.8 kept the id as Mojang hands it out, without dashes. */
public final class SessionShim {
    private SessionShim() {}

    public static String getPlayerID(Session session) {
        UUID id = session.getUuidOrNull();
        return id != null ? id.toString().replace("-", "") : null;
    }

    public static String getToken(Session session) {
        return session.getAccessToken();
    }
}
