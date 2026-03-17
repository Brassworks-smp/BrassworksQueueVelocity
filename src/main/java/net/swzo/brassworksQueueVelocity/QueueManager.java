package net.swzo.brassworksQueueVelocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class QueueManager {
    private final ProxyServer server;
    private final Logger logger;
    private final QueueConfig config;
    private final PriorityStorage priorityStorage;

    private final List<UUID> adminQueue = Collections.synchronizedList(new LinkedList<>());
    private final List<UUID> priorityQueue = Collections.synchronizedList(new LinkedList<>());
    private final List<UUID> regularQueue = Collections.synchronizedList(new LinkedList<>());

    private final ConcurrentHashMap<UUID, Long> pendingConnects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SavedPosition> savedPositions = new ConcurrentHashMap<>();

    private volatile long pauseUntil = 0;
    private volatile long lastSendTime = 0;

    private static final int MAX_JOINS_PER_TICK = 1;
    private static final long SEND_COOLDOWN_MS = 2500; 

    private static class SavedPosition {
        final long disconnectTime;
        final int index;
        final int queueType; 

        SavedPosition(long disconnectTime, int index, int queueType) {
            this.disconnectTime = disconnectTime;
            this.index = index;
            this.queueType = queueType;
        }
    }

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
        UUID uuid = player.getUniqueId();
        if (isQueued(player)) return;
        SavedPosition pos = savedPositions.remove(uuid);
        if (pos != null && (System.currentTimeMillis() - pos.disconnectTime <= 10000)) {
            if (pos.queueType == 0) adminQueue.add(Math.min(pos.index, adminQueue.size()), uuid);
            else if (pos.queueType == 1) priorityQueue.add(Math.min(pos.index, priorityQueue.size()), uuid);
            else regularQueue.add(Math.min(pos.index, regularQueue.size()), uuid);

            logger.info("[Brassworks Queue] Restored {} to their previous queue position.", player.getUsername());
        } else {

            if (player.hasPermission("bwqueue.admin")) {
                adminQueue.add(uuid);
                logger.info("[Brassworks Queue] Added {} to ADMIN queue.", player.getUsername());
            } else if (priorityStorage.hasPriority(uuid) || player.hasPermission("bwqueue.priority")) {
                priorityQueue.add(uuid);
                logger.info("[Brassworks Queue] Added {} to PRIORITY queue.", player.getUsername());
            } else {
                regularQueue.add(uuid);
                logger.info("[Brassworks Queue] Added {} to REGULAR queue.", player.getUsername());
            }
        }
        forceUpdate(player);
    }

    public void forceUpdate(Player player) {
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("brassworksqueuevelocity").get().getInstance().get(), this::processQueue).delay(50, TimeUnit.MILLISECONDS).schedule();
    }

    public boolean isQueued(Player player) {
        UUID uuid = player.getUniqueId();
        return adminQueue.contains(uuid) || priorityQueue.contains(uuid) || regularQueue.contains(uuid);
    }

    public void triggerCooldown() {
        this.pauseUntil = System.currentTimeMillis() + 3000;
    }

    public void markFinished(Player player) {
        pendingConnects.remove(player.getUniqueId());
    }

    public void handleDisconnect(UUID uuid) {
        pendingConnects.remove(uuid);
        long now = System.currentTimeMillis();

        synchronized (adminQueue) {
            int idx = adminQueue.indexOf(uuid);
            if (idx != -1) {
                adminQueue.remove(idx);
                savedPositions.put(uuid, new SavedPosition(now, idx, 0));
                return;
            }
        }
        synchronized (priorityQueue) {
            int idx = priorityQueue.indexOf(uuid);
            if (idx != -1) {
                priorityQueue.remove(idx);
                savedPositions.put(uuid, new SavedPosition(now, idx, 1));
                return;
            }
        }
        synchronized (regularQueue) {
            int idx = regularQueue.indexOf(uuid);
            if (idx != -1) {
                regularQueue.remove(idx);
                savedPositions.put(uuid, new SavedPosition(now, idx, 2));
                return;
            }
        }
    }

    private void processQueue() {
        if (System.currentTimeMillis() < pauseUntil) return;

        RegisteredServer backend = server.getServer(config.backendServer).orElse(null);
        if (backend == null) return;

        long now = System.currentTimeMillis();
        pendingConnects.entrySet().removeIf(e -> now - e.getValue() > 15000);

        backend.ping().whenComplete((serverPing, throwable) -> {
            boolean isOnline = (serverPing != null && throwable == null);

            int realOnline = (isOnline) ? serverPing.getPlayers().map(p -> p.getOnline()).orElse(0) : 0;
            int realMax = (isOnline) ? serverPing.getPlayers().map(p -> p.getMax()).orElse(config.hardMaxPlayers) : config.hardMaxPlayers;

            int effectiveOnline = realOnline + pendingConnects.size();
            int sentThisTick = 0;

            boolean onCooldown = (System.currentTimeMillis() - lastSendTime < SEND_COOLDOWN_MS);
            int allowedToSend = onCooldown ? 0 : MAX_JOINS_PER_TICK;

            int sentAdmins = processSingleQueue(adminQueue, backend, effectiveOnline, realMax, true, isOnline, allowedToSend - sentThisTick);
            sentThisTick += sentAdmins;
            effectiveOnline += sentAdmins;

            int sentPriority = processSingleQueue(priorityQueue, backend, effectiveOnline, realMax, false, isOnline, allowedToSend - sentThisTick);
            sentThisTick += sentPriority;
            effectiveOnline += sentPriority;

            processSingleQueue(regularQueue, backend, effectiveOnline, realMax, false, isOnline, allowedToSend - sentThisTick);
        });
    }

    private int processSingleQueue(List<UUID> queue, RegisteredServer backend, int currentOnline, int maxPlayers, boolean bypass, boolean isBackendOnline, int allowedToSend) {
        if (queue.isEmpty()) return 0;

        int movedPlayers = 0;
        int position = 1;

        synchronized (queue) {
            var iterator = queue.iterator();

            while (iterator.hasNext()) {
                UUID uuid = iterator.next();
                Optional<Player> optPlayer = server.getPlayer(uuid);

                if (optPlayer.isEmpty() || !optPlayer.get().isActive() || isConnectedToBackend(optPlayer.get())) {
                    if (optPlayer.isPresent()) {
                        sendExitJson(optPlayer.get());
                        markFinished(optPlayer.get());
                    }
                    iterator.remove();
                    continue;
                }

                Player player = optPlayer.get();
                if (!isConnectedToLimbo(player)) continue;

                boolean canSend = isBackendOnline && (bypass || currentOnline < maxPlayers);

                if (position == 1 && canSend && allowedToSend > 0) {
                    logger.info("[Brassworks Queue] Sending {} to backend.", player.getUsername());
                    sendExitJson(player);

                    pendingConnects.put(uuid, System.currentTimeMillis());
                    lastSendTime = System.currentTimeMillis(); 
                    player.createConnectionRequest(backend).fireAndForget();
                    iterator.remove();

                    if (!bypass) currentOnline++;
                    movedPlayers++;
                    allowedToSend--;
                } else {
                    int estTime = position * 2;
                    sendQueueJson(player, position, queue.size(), estTime);
                    position++;
                }
            }
        }
        return movedPlayers;
    }

    public void removeFromQueue(Player player) {
        UUID uuid = player.getUniqueId();
        adminQueue.remove(uuid);
        priorityQueue.remove(uuid);
        regularQueue.remove(uuid);
        pendingConnects.remove(uuid);
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