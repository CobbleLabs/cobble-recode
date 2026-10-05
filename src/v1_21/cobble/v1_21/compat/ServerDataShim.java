package cobble.v1_21.compat;

import net.minecraft.client.network.ServerInfo;

/** 1.8.9's ServerData on 1.21.11's ServerInfo. */
public final class ServerDataShim {
    private ServerDataShim() {}

    public static String serverIP(ServerInfo server) {
        return server.address;
    }
}
