package net.swzo.brassworksQueueVelocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class QueueCommands implements SimpleCommand {

    private final ProxyServer server;
    private final QueueManager queueManager;
    private final PriorityStorage priorityStorage;
    private final QueueConfig config;

    public QueueCommands(ProxyServer server, QueueManager queueManager, PriorityStorage priorityStorage, QueueConfig config) {
        this.server = server;
        this.queueManager = queueManager;
        this.priorityStorage = priorityStorage;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            invocation.source().sendMessage(Component.text("Usage: /bwqueue <addpriority|removepriority|sendtoqueue> <player>"));
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("addpriority")) {
            if (!invocation.source().hasPermission("bwqueue.admin")) return;
            handlePriority(invocation, args, true);
        }
        else if (sub.equals("removepriority")) {
            if (!invocation.source().hasPermission("bwqueue.admin")) return;
            handlePriority(invocation, args, false);
        }
        else if (sub.equals("sendtoqueue")) {
            if (!invocation.source().hasPermission("bwqueue.admin")) return;
            handleSendToQueue(invocation, args);
        }
    }

    private void handlePriority(Invocation invocation, String[] args, boolean add) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Please specify a player name.", NamedTextColor.RED));
            return;
        }
        String targetName = args[1];

        Optional<Player> onlineTarget = server.getPlayer(targetName);
        if (onlineTarget.isPresent()) {
            UUID uuid = onlineTarget.get().getUniqueId();
            if (add) priorityStorage.addPlayer(uuid);
            else priorityStorage.removePlayer(uuid);

            invocation.source().sendMessage(Component.text((add ? "Added" : "Removed") + " priority for " + targetName, NamedTextColor.GREEN));
        } else {
            invocation.source().sendMessage(Component.text("Player not found online. (Offline lookup not implemented in this snippet)", NamedTextColor.RED));
        }
    }

    private void handleSendToQueue(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Please specify a player.", NamedTextColor.RED));
            return;
        }

        Optional<Player> target = server.getPlayer(args[1]);
        if (target.isEmpty()) {
            invocation.source().sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return;
        }

        Player player = target.get();
        RegisteredServer limbo = server.getServer(config.limboServer).orElse(null);

        if (limbo == null) {
            invocation.source().sendMessage(Component.text("Limbo server not defined.", NamedTextColor.RED));
            return;
        }

        invocation.source().sendMessage(Component.text("Sending " + player.getUsername() + " to queue...", NamedTextColor.GREEN));

        player.createConnectionRequest(limbo).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                queueManager.addToQueue(player);
                player.sendMessage(Component.text("You have been moved to the queue by an admin.", NamedTextColor.YELLOW));
            } else {
                invocation.source().sendMessage(Component.text("Failed to connect player to Limbo.", NamedTextColor.RED));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("addpriority", "removepriority", "sendtoqueue");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("bwqueue.admin");
    }
}