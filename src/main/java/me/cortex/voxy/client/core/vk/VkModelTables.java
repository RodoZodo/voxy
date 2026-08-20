package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Device-side tables the GPU mesher reads: blockId -> modelId ({@code idMappings}), modelId ->
 * packed metadata ({@code metadataCache}). Host-visible; re-uploaded on change. The real data is
 * populated by the model baking subsystem (next increment); this also supports synthetic seeding
 * for the mesher verification path.
 */
public final class VkModelTables implements AutoCloseable {
    public static final int MAX_BLOCK_ID = 1 << 20;
    public static final int MAX_MODEL_ID = 1 << 16;

    private final VkBuffer idMappingsBuffer;
    private final VkBuffer metadataCacheBuffer;
    private final VkBuffer fluidLUTBuffer;
    private final int[] idMappings = new int[MAX_BLOCK_ID];
    private final long[] metadataCache = new long[MAX_MODEL_ID];
    private final int[] fluidLUT = new int[MAX_MODEL_ID];
    private long revision;
    private long lastUploaded;

    public VkModelTables(long vma) {
        this.idMappingsBuffer = VkBuffer.hostVisible(vma, MAX_BLOCK_ID * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.metadataCacheBuffer = VkBuffer.hostVisible(vma, MAX_MODEL_ID * 8L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        this.fluidLUTBuffer = VkBuffer.hostVisible(vma, MAX_MODEL_ID * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        java.util.Arrays.fill(this.idMappings, -1);
        java.util.Arrays.fill(this.fluidLUT, -1);
    }

    /** Register (or replace) a blockId -> modelId mapping with its packed metadata. */
    public void update(int blockId, int modelId, long metadata) {
        this.idMappings[blockId] = modelId;
        this.metadataCache[modelId] = metadata;
        this.revision++;
    }

    /** Mirror the factory's CPU arrays if the factory has baked new models. */
    public void syncFromFactory(me.cortex.voxy.client.core.model.ModelFactory factory) {
        long rev = factory.getBakeRevision();
        if (rev == this.revision) {
            return;
        }
        //Copy the factory's arrays (IdNotYetComputedException handling is done by the mesher pre-flight)
        System.arraycopy(factory.getIdMappingsView(), 0, this.idMappings, 0, MAX_BLOCK_ID);
        System.arraycopy(factory.getMetadataCacheView(), 0, this.metadataCache, 0, MAX_MODEL_ID);
        System.arraycopy(factory.getFluidStateLUTView(), 0, this.fluidLUT, 0, MAX_MODEL_ID);
        this.revision = rev;
    }

    /** Re-upload the tables to the device if they changed. Call before the mesh dispatch. */
    public void upload(VkCommandBuffer cb) {
        if (this.revision == this.lastUploaded) {
            return;
        }
        this.lastUploaded = this.revision;
        try (var stack = MemoryStack.stackPush()) {
            var ids = stack.calloc(this.idMappings.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : this.idMappings) {
                ids.putInt(v);
            }
            ids.position(0);
            this.idMappingsBuffer.write(ids);

            var meta = stack.calloc(this.metadataCache.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : this.metadataCache) {
                meta.putLong(v);
            }
            meta.position(0);
            this.metadataCacheBuffer.write(meta);

            var fluid = stack.calloc(this.fluidLUT.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : this.fluidLUT) {
                fluid.putInt(v);
            }
            fluid.position(0);
            this.fluidLUTBuffer.write(fluid);
        }
        VkSync.memoryBarrier(cb);//host write -> shader read
    }

    public VkBuffer idMappingsBuffer() {
        return this.idMappingsBuffer;
    }

    public VkBuffer metadataCacheBuffer() {
        return this.metadataCacheBuffer;
    }

    public VkBuffer fluidLUTBuffer() {
        return this.fluidLUTBuffer;
    }

    @Override
    public void close() {
        this.idMappingsBuffer.close();
        this.metadataCacheBuffer.close();
        this.fluidLUTBuffer.close();
    }
}
