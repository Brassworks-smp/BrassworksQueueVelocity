package net.swzo.brassworksQueueVelocity;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class QueueConfig {
    private final Path dataDirectory;
    private final Logger logger;
    private Toml toml;

    public String limboServer = "limbo";
    public String backendServer = "survival";
    public int hardMaxPlayers = 30;
    public int refreshRateMs = 1000;

    public QueueConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() {
        try {
            if (!Files.exists(dataDirectory)) Files.createDirectories(dataDirectory);
            File file = dataDirectory.resolve("config.toml").toFile();

            if (!file.exists()) {
                try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
                    if (in != null) Files.copy(in, file.toPath());
                    else Files.createFile(file.toPath());
                }
            }

            toml = new Toml().read(file);
            limboServer = toml.getString("servers.limbo", "limbo");
            backendServer = toml.getString("servers.backend", "survival");
            hardMaxPlayers = toml.getLong("settings.max_players", 50L).intValue();
            refreshRateMs = toml.getLong("settings.refresh_rate_ms", 1000L).intValue();

        } catch (Exception e) {
            logger.error("[Brassworks Queue] Failed to load config, using defaults.", e);
        }
    }
}