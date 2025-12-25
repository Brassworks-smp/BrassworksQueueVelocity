package net.swzo.brassworksQueueVelocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;

public class QueueListener {

    private final ProxyServer server;
    private final QueueManager queueManager;
    private final QueueConfig config;
    private final Logger logger;

    public QueueListener(ProxyServer server, QueueManager queueManager, QueueConfig config, Logger logger) {
        this.server = server;
        this.queueManager = queueManager;
        this.config = config;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(PlayerChooseInitialServerEvent event) {
        RegisteredServer backend = server.getServer(config.backendServer).orElse(null);
        if (backend != null) {
            event.setInitialServer(backend);
        }
    }

    @Subscribe
    public void onServerConnect(ServerPostConnectEvent event) {
        String serverName = event.getPlayer().getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("unknown");

        if (serverName.equals(config.limboServer)) {
            if (queueManager.isQueued(event.getPlayer())) {
                queueManager.forceUpdate(event.getPlayer());
            }
        } else if (serverName.equals(config.backendServer)) {
            queueManager.sendExitJson(event.getPlayer());
            queueManager.removeFromQueue(event.getPlayer());
        }
    }

    @Subscribe
    public void onKick(KickedFromServerEvent event) {
        if (!event.getServer().getServerInfo().getName().equals(config.backendServer)) return;

        if (event.kickedDuringServerConnect()) {

        }

        RegisteredServer limbo = server.getServer(config.limboServer).orElse(null);

        if (limbo != null) {
            Optional<Component> reason = event.getServerKickReason();
            Component message = reason.orElse(Component.text("Connection lost."));
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo, message));
            queueManager.addToQueue(event.getPlayer());
        }
    }
}