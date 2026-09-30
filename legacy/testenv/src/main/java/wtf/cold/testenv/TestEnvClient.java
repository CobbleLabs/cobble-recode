package wtf.cold.testenv;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TestEnvClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobble-testenv");

    private static final String PID_FILE_PROPERTY = "cobble.pidfile";
    private static final String AUTOLOAD_PROPERTY = "cobble.autoload";

    @Override
    public void onInitializeClient() {
        long pid = ProcessHandle.current().pid();
        LOGGER.info("Cobble Recode test environment online");
        LOGGER.info("  pid                = {}", pid);
        LOGGER.info("  runtime namespace  = {}", FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace());
        LOGGER.info("  game directory     = {}", FabricLoader.getInstance().getGameDir().toAbsolutePath());

        writePidFile(pid);
        autoLoadPayload();
    }

    private static void writePidFile(long pid) {
        String target = System.getProperty(PID_FILE_PROPERTY);
        if (target == null || target.isBlank()) {
            return;
        }
        Path path = Paths.get(target).toAbsolutePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Long.toString(pid));
            LOGGER.info("  pid file           = {}", path);
        } catch (IOException exception) {
            LOGGER.error("failed to write pid file {}", path, exception);
        }
    }

    private static void autoLoadPayload() {
        String payload = System.getProperty(AUTOLOAD_PROPERTY);
        if (payload == null || payload.isBlank()) {
            return;
        }
        Path path = Paths.get(payload).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            LOGGER.error("cobble.autoload points at a missing file: {}", path);
            return;
        }
        try {
            System.load(path.toString());
            LOGGER.info("  payload            = {} (loaded in-process)", path);
        } catch (Throwable throwable) {
            LOGGER.error("failed to load payload {}", path, throwable);
        }
    }
}
