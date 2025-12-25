package net.swzo.brassworksQueueVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "brassworksqueuevelocity",
        name = "BrassworksQueueVelocity",
        version = "1.0.0",
        url = "https://brassworks.opnsoc.org/",
        authors = {"swzo"}
)
public class BrassworksQueueVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private QueueConfig config;
    private PriorityStorage priorityStorage;
    private QueueManager queueManager;

    @Inject
    public BrassworksQueueVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {

        this.config = new QueueConfig(dataDirectory, logger);
        this.config.load();

        this.priorityStorage = new PriorityStorage(dataDirectory, logger);
        this.priorityStorage.load();

        this.queueManager = new QueueManager(server, logger, config, priorityStorage);
        this.queueManager.startTask();

        server.getEventManager().register(this, new QueueListener(server, queueManager, config, logger));
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("bwqueue").build(),
                new QueueCommands(server, queueManager, priorityStorage, config)
        );

        logger.info("[Brassworks Queue] Brassworks Queue System Initialized.");
    }
}