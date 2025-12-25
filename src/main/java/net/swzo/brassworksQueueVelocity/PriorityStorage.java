package net.swzo.brassworksQueueVelocity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PriorityStorage {
    private final Path filePath;
    private final Logger logger;
    private final Gson gson = new Gson();
    private Set<UUID> priorityUuids = Collections.synchronizedSet(new HashSet<>());

    public PriorityStorage(Path dataDirectory, Logger logger) {
        this.filePath = dataDirectory.resolve("priority_users.json");
        this.logger = logger;
    }

    public void load() {
        if (!filePath.toFile().exists()) return;
        try (FileReader reader = new FileReader(filePath.toFile())) {
            Set<UUID> loaded = gson.fromJson(reader, new TypeToken<Set<UUID>>(){}.getType());
            if (loaded != null) priorityUuids.addAll(loaded);
        } catch (Exception e) {
            logger.error("[Brassworks Queue] Failed to load priority users.", e);
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            gson.toJson(priorityUuids, writer);
        } catch (Exception e) {
            logger.error("[Brassworks Queue] Failed to save priority users.", e);
        }
    }

    public void addPlayer(UUID uuid) {
        priorityUuids.add(uuid);
        save();
    }

    public void removePlayer(UUID uuid) {
        priorityUuids.remove(uuid);
        save();
    }

    public boolean hasPriority(UUID uuid) {
        return priorityUuids.contains(uuid);
    }
}