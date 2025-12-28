package net.swzo.brassworksQueueVelocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

public class TotalPlayerCountManager {

    private final ProxyServer server;

    public TotalPlayerCountManager(ProxyServer server) {
        this.server = server;
    }

    public int getTotalPlayerCount() {
        return server.getAllServers().stream()
                .mapToInt(s -> s.getPlayersConnected() != null ? s.getPlayersConnected().size() : 0)
                .sum();
    }

    public void broadcastTotalPlayerCount(String messagePrefix) {
        Component message = Component.text(messagePrefix + getTotalPlayerCount());
        server.getAllPlayers().forEach(player -> player.sendMessage(message));
    }
}
