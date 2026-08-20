package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * GPU meshing service: dedup + LOD-aware priority + bake-miss pre-flight + submit to
 * {@link VkMeshGenerator}. Wired as the {@code meshRequestConsumer} for
 * {@link me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager}.
 *
 * <p>All queue ops are thread-safe (called from ingest/world threads). {@link #drain()} is
 * called on the render thread at splice-2 (pre-commit) and submits a budget of jobs to the
 * generator.
 */
public final class GpuMeshService {
    private static final int MAX_HOLDING_SECTION_COUNT = 1000;
    private static final int BUDGET_PER_DRAIN = 4;

    private final WorldEngine world;
    private final ModelFactory factory;
    private final ModelBakerySubsystem bakery;
    private final VkMeshGenerator generator;

    private final Long2ObjectOpenHashMap<MeshTask> taskMap = new Long2ObjectOpenHashMap<>();
    private final PriorityBlockingQueue<MeshTask> queue = new PriorityBlockingQueue<>(512, (a, b) -> Long.compareUnsigned(a.priority, b.priority));
    private final AtomicLong counter = new AtomicLong();
    private final StampedLock lock = new StampedLock();

    private static final class MeshTask {
        final long position;
        int attempts;
        int addin;
        long priority;

        MeshTask(long position) {
            this.position = position;
        }

        void updatePriority() {
            int lvl = Math.min(WorldEngine.MAX_LOD_LAYER - WorldEngine.getLevel(this.position), 3);
            if (lvl < 0) lvl = 0;
            long p = (((long) (lvl * 3 + Math.min(this.attempts, 3)) * 2 + this.addin) << 32) | (counterValue() & 0xFFFFFFFFL);
            this.priority = p;
        }

        private static long counterValue() {
            //Use a global counter; for now just use System.nanoTime low bits as tie-breaker
            return System.nanoTime();
        }
    }

    public GpuMeshService(WorldEngine world, ModelFactory factory, ModelBakerySubsystem bakery, VkMeshGenerator generator) {
        this.world = world;
        this.factory = factory;
        this.bakery = bakery;
        this.generator = generator;
    }

    /** Called from any thread (SectionUpdateRouter). */
    public void enqueue(long pos) {
        long stamp = this.lock.writeLock();
        try {
            if (this.taskMap.containsKey(pos)) {
                return;
            }
            var task = new MeshTask(pos);
            //Low LOD sections get a one-time addin to let models bake (mirrors RenderGenerationService)
            task.addin = WorldEngine.getLevel(pos) > 2 ? 1 : 0;
            task.updatePriority();
            this.taskMap.put(pos, task);
            this.queue.add(task);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    /** Called on the render thread at splice-2 (pre-commit). Drains a budget of tasks. */
    public void drain() {
        if (this.generator == null) {
            return;
        }
        for (int i = 0; i < BUDGET_PER_DRAIN; i++) {
            MeshTask task = this.queue.poll();
            if (task == null) {
                break;
            }
            long stamp = this.lock.writeLock();
            try {
                this.taskMap.remove(task.position);
            } finally {
                this.lock.unlockWrite(stamp);
            }

            //Try to acquire the section (if it no longer exists, treat as empty)
            WorldSection section = this.world.acquireIfExists(task.position);
            if (section == null) {
                //Section gone -> nothing to mesh; the node will be removed via other path
                continue;
            }
            try {
                //Pre-flight: check all non-air blockIds have a baked model
                if (!this.preflight(section)) {
                    //Requeue with higher priority (attempts++) to retry after bakes
                    task.attempts++;
                    task.addin = 0;
                    task.updatePriority();
                    long wStamp = this.lock.writeLock();
                    try {
                        //Dedup re-insert (if not already re-enqueued by another trigger)
                        if (!this.taskMap.containsKey(task.position)) {
                            this.taskMap.put(task.position, task);
                            this.queue.add(task);
                        }
                    } finally {
                        this.lock.unlockWrite(wStamp);
                    }
                    continue;
                }

                //Extract raw section data + 6 neighbor face planes
                long[] raw = new long[32768];
                System.arraycopy(section._unsafeGetRawDataArray(), 0, raw, 0, 32768);
                long[] neighbors = new long[6144];
                this.extractNeighborPlanes(section, neighbors);

                byte childExistence = section.getNonEmptyChildren();
                this.generator.submit(task.position, childExistence, raw, neighbors);
            } finally {
                section.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
    }

    private boolean preflight(WorldSection section) {
        long[] raw = section._unsafeGetRawDataArray();
        boolean ok = true;
        for (int i = 0; i < raw.length; i++) {
            long id = raw[i];
            if ((id & (((1L << 20) - 1) << 27)) == 0) {
                continue;
            }
            int blockId = (int) ((id >> 27) & ((1 << 20) - 1));
            if (!this.factory.hasModelForBlockId(blockId)) {
                this.bakery.requestBlockBake(blockId);
                ok = false;
            }
        }
        //Also check neighbor planes for outer-face culling (conservative: check all 6 neighbors if they exist)
        // For now we check the 6 neighbor planes we will extract; if any neighbor blockId is missing, request bake
        // This is done in extractNeighborPlanes' caller (drain) after extracting, but we can also check here
        // by acquiring neighbors. To keep it simple, we check the 6 neighbor sections' face planes if they exist.
        // For 4B opaque-only, the outer pass only needs isFullyOpaque check, which needs the neighbor's metadata.
        // So we need to ensure neighbor blockIds are also baked.
        // We do a conservative check: acquire each neighbor section if it exists and scan its face plane.
        int lvl = section.lvl;
        int x = section.x;
        int y = section.y;
        int z = section.z;
        int[][] deltas = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for (int[] d : deltas) {
            WorldSection nSec = this.world.acquireIfExists(lvl, x + d[0], y + d[1], z + d[2]);
            if (nSec == null) continue;
            try {
                long[] nRaw = nSec._unsafeGetRawDataArray();
                //Scan the face plane that touches our section (the 32x32 face)
                // For each neighbor, the face is the side adjacent to us
                // To avoid per-voxel face logic, just scan the whole face plane (1024 voxels)
                for (int j = 0; j < 1024; j++) {
                    //The face plane index depends on direction; we just scan the whole section's relevant face
                    // For -x neighbor, the +x face (x=31) touches us; for +x neighbor, -x face (x=0)
                    // We can just check all voxels on that face plane
                    int idx;
                    if (d[0] == -1) idx = (j << 5) + 31; // y*32+z -> x=31
                    else if (d[0] == 1) idx = j << 5; // x=0
                    else if (d[1] == -1) idx = j | (0x1F << 10); // y=31
                    else if (d[1] == 1) idx = j; // y=0
                    else if (d[2] == -1) idx = Integer.expand(j, 0b11111_00000_11111) | (0x1F << 5);
                    else idx = Integer.expand(j, 0b11111_00000_11111);
                    long nid = nRaw[idx];
                    if ((nid & (((1L << 20) - 1) << 27)) == 0) continue;
                    int nBlockId = (int) ((nid >> 27) & ((1 << 20) - 1));
                    if (!this.factory.hasModelForBlockId(nBlockId)) {
                        this.bakery.requestBlockBake(nBlockId);
                        ok = false;
                    }
                }
            } finally {
                nSec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
            if (!ok) {
                //We could break early, but we want to request all missing bakes in one go
            }
        }
        return ok;
    }

    private void extractNeighborPlanes(WorldSection section, long[] out) {
        int lvl = section.lvl;
        int x = section.x;
        int y = section.y;
        int z = section.z;
        //out layout: 0:+1024 -x, 1:+1024 +x, 2:+1024 -y, 3:+1024 +y, 4:+1024 -z, 5:+1024 +z
        // Each plane is 1024 longs (32x32)
        java.util.Arrays.fill(out, 0);
        WorldSection sec;
        sec = this.world.acquireIfExists(lvl, x - 1, y, z);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[i] = raw[(i << 5) + 31];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        sec = this.world.acquireIfExists(lvl, x + 1, y, z);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[1024 + i] = raw[i << 5];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        sec = this.world.acquireIfExists(lvl, x, y - 1, z);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[2048 + i] = raw[i | (0x1F << 10)];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        sec = this.world.acquireIfExists(lvl, x, y + 1, z);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[3072 + i] = raw[i];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        sec = this.world.acquireIfExists(lvl, x, y, z - 1);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[4096 + i] = raw[Integer.expand(i, 0b11111_00000_11111) | (0x1F << 5)];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
        sec = this.world.acquireIfExists(lvl, x, y, z + 1);
        if (sec != null) {
            try {
                var raw = sec._unsafeGetRawDataArray();
                for (int i = 0; i < 1024; i++) {
                    out[5120 + i] = raw[Integer.expand(i, 0b11111_00000_11111)];
                }
            } finally {
                sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
            }
        }
    }

    public void clear() {
        long stamp = this.lock.writeLock();
        try {
            this.taskMap.clear();
            this.queue.clear();
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public int pendingCount() {
        return this.queue.size();
    }
}
