package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Vanilla chunk-load ingest. The original Sodium {@code RenderSectionManager} mixins that
 * fed LoDs on chunk add/remove are not in this Vulkan build, so without this scanner nothing
 * is ever ingested even when Voxy is "enabled".
 */
public final class ClientChunkIngest {
    private static final LongOpenHashSet INGESTED = new LongOpenHashSet();
    private static int lastLogged = 0;
    private static Object lastDimension;

    private ClientChunkIngest() {
    }

    public static void reset() {
        INGESTED.clear();
        lastLogged = 0;
        lastDimension = null;
    }

    public static int ingestedCount() {
        return INGESTED.size();
    }

    public static void noteAttempt(LevelChunk chunk) {
        if (chunk == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        if (VoxyCommon.getInstance() == null) {
            return;
        }
        if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
            mark(chunk);
        }
    }

    public static void tick(Minecraft client) {
        if (client == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return;
        }
        var level = client.level;
        var player = client.player;
        if (level == null || player == null) {
            return;
        }
        var dimension = level.dimension();
        if (lastDimension == null || !lastDimension.equals(dimension)) {
            INGESTED.clear();
            lastLogged = 0;
            lastDimension = dimension;
        }
        var source = level.getChunkSource();
        if (!(source instanceof ClientChunkCache cache)) {
            return;
        }

        try {
            var added = cache.addedLoadedChunks();
            if (added != null && !added.isEmpty()) {
                var iterator = added.iterator();
                while (iterator.hasNext()) {
                    tryIngestPacked(cache, iterator.nextLong());
                }
            }
        } catch (Throwable ignored) {
        }

        ChunkPos center = player.chunkPosition();
        int radius = 8;
        try {
            radius = Math.min(client.options.getEffectiveRenderDistance() + 2, 32);
        } catch (Throwable ignored) {
        }
        int budget = 24;
        for (int dz = -radius; dz <= radius && budget > 0; dz++) {
            for (int dx = -radius; dx <= radius && budget > 0; dx++) {
                long packed = ChunkPos.pack(center.x() + dx, center.z() + dz);
                if (INGESTED.contains(packed)) {
                    continue;
                }
                if (tryIngestPacked(cache, packed)) {
                    budget--;
                }
            }
        }

        int count = INGESTED.size();
        if (count > 0 && (count == 1 || count - lastLogged >= 50)) {
            lastLogged = count;
            Logger.info("Voxy: ingested " + count + " chunks, queue=" + instance.getIngestService().getTaskCount());
        }
    }

    private static boolean tryIngestPacked(ClientChunkCache cache, long packed) {
        if (INGESTED.contains(packed)) {
            return false;
        }
        int x = ChunkPos.getX(packed);
        int z = ChunkPos.getZ(packed);
        var chunk = cache.getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) {
            return false;
        }
        if (!VoxelIngestService.tryAutoIngestChunk(chunk)) {
            return false;
        }
        mark(chunk);
        return true;
    }

    private static void mark(LevelChunk chunk) {
        INGESTED.add(chunk.getPos().pack());
    }
}
