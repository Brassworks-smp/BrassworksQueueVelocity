package net.swzo.brassworksQueueVelocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class QueueManager {
    private final ProxyServer server;
    private final Logger logger;
    private final QueueConfig config;
    private final PriorityStorage priorityStorage;

    private final ConcurrentLinkedQueue<Player> adminQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Player> priorityQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Player> regularQueue = new ConcurrentLinkedQueue<>();

    public QueueManager(ProxyServer server, Logger logger, QueueConfig config, PriorityStorage priorityStorage) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.priorityStorage = priorityStorage;
    }

    public void startTask() {
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("brassworksqueuevelocity").get().getInstance().get(), this::processQueue)
                .repeat(config.refreshRateMs, TimeUnit.MILLISECONDS)
                .schedule();
    }

    public void addToQueue(Player player) {
        if (isQueued(player)) return;

        if (player.hasPermission("bwqueue.admin")) {
            adminQueue.add(player);
            logger.info("[Brassworks Queue] Added {} to ADMIN queue.", player.getUsername());
        } else if (priorityStorage.hasPriority(player.getUniqueId()) || player.hasPermission("bwqueue.priority")) {
            priorityQueue.add(player);
            logger.info("[Brassworks Queue] Added {} to PRIORITY queue.", player.getUsername());
        } else {
            regularQueue.add(player);
            logger.info("[Brassworks Queue] Added {} to REGULAR queue.", player.getUsername());
        }

        forceUpdate(player);
    }

    public void forceUpdate(Player player) {
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("brassworksqueuevelocity").get().getInstance().get(), this::processQueue)
                .delay(50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    public boolean isQueued(Player player) {
        return adminQueue.contains(player) || priorityQueue.contains(player) || regularQueue.contains(player);
    }

    private void processQueue() {
        RegisteredServer backend = server.getServer(config.backendServer).orElse(null);
        if (backend == null) return;

        backend.ping().whenComplete((serverPing, throwable) -> {
            boolean isOnline = (serverPing != null && throwable == null);

            int realOnline = (isOnline) ? serverPing.getPlayers().map(p -> p.getOnline()).orElse(0) : 0;
            int realMax = (isOnline) ? serverPing.getPlayers().map(p -> p.getMax()).orElse(config.hardMaxPlayers) : config.hardMaxPlayers;

            processSingleQueue(adminQueue, backend, realOnline, realMax, true, isOnline);

            int filledSlots = processSingleQueue(priorityQueue, backend, realOnline, realMax, false, isOnline);
            if (isOnline) realOnline += filledSlots;

            processSingleQueue(regularQueue, backend, realOnline, realMax, false, isOnline);
        });
    }

    private int processSingleQueue(ConcurrentLinkedQueue<Player> queue, RegisteredServer backend, int realOnline, int realMax, boolean bypass, boolean isBackendOnline) {
        if (queue.isEmpty()) return 0;

        int movedPlayers = 0;
        int position = 1;
        var iterator = queue.iterator();

        while (iterator.hasNext()) {
            Player player = iterator.next();

            if (!player.isActive() || isConnectedToBackend(player)) {
                sendExitJson(player);
                iterator.remove();
                continue;
            }

            if (!isConnectedToLimbo(player)) {
                continue;
            }

            if (isBackendOnline && position == 1 && (bypass || realOnline < realMax)) {
                logger.info("[Brassworks Queue] Sending {} to backend.", player.getUsername());
                sendExitJson(player);
                player.createConnectionRequest(backend).fireAndForget();
                iterator.remove();

                if (!bypass) {
                    realOnline++;
                    movedPlayers++;
                }
            } else {

                int estTime = position * 2; 
                sendQueueJson(player, position, queue.size(), estTime);
                position++;
            }
        }
        return movedPlayers;
    }

    public void removeFromQueue(Player player) {
        adminQueue.remove(player);
        priorityQueue.remove(player);
        regularQueue.remove(player);
    }

    private boolean isConnectedToBackend(Player player) {
        return player.getCurrentServer().map(s -> s.getServerInfo().getName().equals(config.backendServer)).orElse(false);
    }

    private boolean isConnectedToLimbo(Player player) {
        return player.getCurrentServer().map(s -> s.getServerInfo().getName().equals(config.limboServer)).orElse(false);
    }

    private void sendQueueJson(Player player, int pos, int total, int time) {
        JsonObject json = new JsonObject();
        json.addProperty("bw_secret", "BRASSWORKS_SECURE_QUEUE");
        json.addProperty("type", "UPDATE");
        json.addProperty("pos", pos);
        json.addProperty("total", total);
        json.addProperty("time", time);
        player.sendMessage(Component.text(json.toString()));
    }

    public void sendExitJson(Player player) {
        JsonObject json = new JsonObject();
        json.addProperty("bw_secret", "BRASSWORKS_SECURE_QUEUE");
        json.addProperty("type", "EXIT");
        player.sendMessage(Component.text(json.toString()));
    }
}